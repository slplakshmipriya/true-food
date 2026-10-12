"""BareLabel share backend (Cloud Run).

Serves two things:
  /.well-known/assetlinks.json  App Links verification for barelabel.online
  /p/{gtin}                     Open Graph preview page for shared products

The /p/{gtin} page is what chat apps (WhatsApp, Messages, ...) unfurl when a
user shares a product link. Product facts arrive as query params from the app
(v=clean|dirty, score=, flagged=, n=name, img=image URL, m=merchants) so this
service needs no API keys. When the app didn't have a cached product image,
the backend falls back to Open Food Facts (free, keyless) for the GTIN.
Retailer chips link to each merchant's site search for the product — exact
per-offer deep links stay in the app, where the UPCitemdb lookup and
affiliate logic live. Tapping "Open in BareLabel" fires an Android intent
that opens the product in the app when installed and falls through to the
Play Store listing otherwise.
"""

import html
import json
import os
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

from fastapi import FastAPI, Query
from fastapi.responses import HTMLResponse, JSONResponse

from pages import landing_page, lookup_page
from verdict import verdict_for

app = FastAPI(title="barelabel-share")

PLAY_STORE_URL = (
    "https://play.google.com/store/apps/details?id=com.barelabel.app"
)
SHARE_HOST = "barelabel.online"
APP_PACKAGE = "com.barelabel.app"
GTIN_RE = re.compile(r"^[0-9]{8,14}$")

ASSETLINKS_PATH = Path(__file__).with_name("assetlinks.json")

# Merchant name (lowercased) -> site-search URL template for the product.
MERCHANT_SEARCH = {
    "amazon": "https://www.amazon.com/s?k={q}",
    "walmart": "https://www.walmart.com/search?q={q}",
    "target": "https://www.target.com/s?searchTerm={q}",
    "kroger": "https://www.kroger.com/search?query={q}",
    "whole foods": "https://www.wholefoodsmarket.com/search?text={q}",
    "whole foods market": "https://www.wholefoodsmarket.com/search?text={q}",
    "costco": "https://www.costco.com/CatalogSearch?dept=All&keyword={q}",
    "instacart": "https://www.instacart.com/store/s?k={q}",
}

# GTIN -> (fetched_at_epoch, image_url|None, product_name|None). 24h TTL.
_OFF_CACHE: dict[str, tuple[float, str | None, str | None]] = {}
_OFF_TTL = 24 * 3600


def merchant_link(merchant: str, product_name: str) -> str:
    """Site-search URL for buying the product at the merchant."""
    tmpl = MERCHANT_SEARCH.get(merchant.strip().lower())
    if tmpl:
        return tmpl.format(q=urllib.parse.quote_plus(product_name))
    q = urllib.parse.quote_plus(f"{merchant} {product_name}")
    return f"https://www.google.com/search?tbm=shop&q={q}"


def off_product(gtin: str) -> tuple[str | None, str | None]:
    """(image_url, product_name) from Open Food Facts (free, keyless).

    Cached 24h. Lets the app omit the img= and n= params — the backend
    fills them in — which keeps shared URLs short.
    """
    now = time.time()
    hit = _OFF_CACHE.get(gtin)
    if hit is not None and now - hit[0] < _OFF_TTL:
        return hit[1], hit[2]
    image: str | None = None
    name: str | None = None
    try:
        req = urllib.request.Request(
            f"https://world.openfoodfacts.org/api/v0/product/{gtin}.json",
            headers={"User-Agent": "BareLabel/1.0 (share preview)"},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        product = data.get("product") or {}
        image = product.get("image_front_url") or product.get("image_url") or None
        name = product.get("product_name") or None
    except Exception:
        pass
    if len(_OFF_CACHE) > 2000:
        _OFF_CACHE.clear()
    _OFF_CACHE[gtin] = (now, image, name)
    return image, name


@app.get("/.well-known/assetlinks.json")
def assetlinks():
    try:
        return JSONResponse(json.loads(ASSETLINKS_PATH.read_text()))
    except (OSError, ValueError):
        return JSONResponse(
            {"error": "assetlinks.json not configured"}, status_code=500
        )


@app.get("/p/{gtin}", response_class=HTMLResponse)
def product_page(
    gtin: str,
    v: str = Query(default=""),
    score: int = Query(default=-1),
    flagged: int = Query(default=0),
    n: str = Query(default=""),
    img: str = Query(default=""),
    m: str = Query(default=""),
):
    if not GTIN_RE.match(gtin):
        return HTMLResponse("<h1>Invalid product link</h1>", status_code=404)

    verdict = "clean" if v == "clean" else ("dirty" if v == "dirty" else "")
    esc = lambda s: html.escape(s, quote=True)  # noqa: E731

    # The app omits img=/n= when Open Food Facts can supply them (keeps
    # shared URLs short); fill in from OFF here. Skip the fetch entirely
    # when the app sent both.
    off_image, off_name = (None, None)
    if not img.strip() or not n.strip():
        off_image, off_name = off_product(gtin)
    name = n.strip() or off_name or "A grocery product"

    if verdict == "clean":
        badge, badge_color = "\u2713 CLEAN", "#166534"
        title = f"{name} \u2014 CLEAN on BareLabel"
    elif verdict == "dirty":
        badge, badge_color = "\u26a0 DIRTY", "#991b1b"
        title = f"{name} \u2014 DIRTY on BareLabel"
    else:
        badge, badge_color = "BareLabel", "#1e40af"
        title = f"{name} \u2014 check it on BareLabel"

    desc_bits = []
    if score >= 0:
        desc_bits.append(f"Clean Score {score}/100")
    if verdict == "dirty":
        desc_bits.append(
            f"{flagged} flagged ingredient{'s' if flagged != 1 else ''}"
        )
    desc_bits.append("See the full ingredient breakdown in BareLabel.")
    description = " \u00b7 ".join(desc_bits)

    canonical = f"https://{SHARE_HOST}/p/{gtin}"

    # Product image: prefer the app's (already verified), else Open Food Facts.
    image = img.strip() or off_image or ""
    og_image = (
        f'<meta property="og:image" content="{esc(image)}" />' if image else ""
    )
    img_tag = (
        f"<img class='product' src='{esc(image)}' alt='' />" if image else ""
    )

    # Retailer chips from the app's merchant names (m=a,b,c).
    merchants = [x.strip() for x in m.split(",") if x.strip()][:6]
    retailer_html = ""
    if merchants:
        chips = " ".join(
            f"<a class='chip' href='{esc(merchant_link(merch, name))}'>"
            f"{esc(merch)}</a>"
            for merch in merchants
        )
        retailer_html = (
            f"<p class='retailers'>Available at<br />{chips}</p>"
        )

    # Smart open button: opens the app when installed, Play Store otherwise.
    intent_url = (
        f"intent://{SHARE_HOST}/p/{gtin}"
        "#Intent;scheme=https;"
        f"package={APP_PACKAGE};"
        f"S.browser_fallback_url={urllib.parse.quote(PLAY_STORE_URL, safe='')};"
        "end"
    )

    page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>{esc(title)}</title>
<meta property="og:title" content="{esc(title)}" />
<meta property="og:description" content="{esc(description)}" />
<meta property="og:type" content="website" />
<meta property="og:url" content="{esc(canonical)}" />
{og_image}
<style>
body{{font-family:system-ui,sans-serif;max-width:560px;margin:48px auto;
padding:0 20px;color:#111827;text-align:center}}
.badge{{display:inline-block;padding:8px 18px;border-radius:999px;font-weight:700;
color:#fff;background:{badge_color};margin:16px 0}}
.btn{{display:inline-block;margin:8px;padding:14px 28px;border-radius:12px;
font-weight:700;text-decoration:none}}
.primary{{background:#2563eb;color:#fff}}
.secondary{{background:#f3f4f6;color:#111827}}
img.product{{max-width:220px;border-radius:12px;margin-top:12px}}
.retailers{{color:#6b7280;font-size:14px;margin:20px 0 4px}}
.chip{{display:inline-block;margin:4px;padding:8px 16px;border-radius:999px;
background:#f3f4f6;color:#111827;text-decoration:none;font-size:14px;
font-weight:600}}
</style>
</head>
<body>
{img_tag}
<h1>{esc(name)}</h1>
<div class="badge">{esc(badge)}</div>
<p>{esc(description)}</p>
{retailer_html}
<p>
<a class="btn primary" href="{esc(intent_url)}">Open in BareLabel</a><br />
<a class="btn secondary" href="{esc(PLAY_STORE_URL)}">Get the app</a>
</p>
</body>
</html>"""
    return HTMLResponse(page)


@app.get("/", response_class=HTMLResponse)
def index():
    return HTMLResponse(landing_page(PLAY_STORE_URL))


# ---------------------------------------------------------------------------
# Web lookup: product search + verdict, powered by the ported verdict engine.
# Needs USDA_API_KEY in the environment (set on the Cloud Run service, not
# in the repo — the same key the Android app bakes into BuildConfig).
# ---------------------------------------------------------------------------

USDA_API_KEY = os.environ.get("USDA_API_KEY", "")
USDA_SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search"


def usda_search(query, page_size=10):
    """Branded USDA search; returns the foods list (possibly empty)."""
    if not USDA_API_KEY:
        return []
    params = urllib.parse.urlencode({
        "query": query,
        "dataType": "Branded",
        "pageSize": page_size,
        "api_key": USDA_API_KEY,
    })
    req = urllib.request.Request(
        f"{USDA_SEARCH_URL}?{params}",
        headers={"User-Agent": "BareLabel/1.0 (web lookup)"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8")).get("foods") or []
    except Exception:
        return []


def pick_product(foods, query):
    """Mirror the app: exact GTIN match, else first result with ingredients."""
    if not foods:
        return None
    if GTIN_RE.match(query or ""):
        needle = query.lstrip("0")
        for f in foods:
            stored = (f.get("gtinUpc") or "").lstrip("0")
            if stored and stored == needle:
                return f
    for f in foods:
        if (f.get("ingredients") or "").strip():
            return f
    return foods[0]


def lookup_product(query):
    """(usda_food, off_image_url) for a product-name or barcode query."""
    query = (query or "").strip()
    if not query:
        return None, None
    foods = usda_search(query)
    # Barcode digits USDA doesn't know: fall back to an OFF name search so
    # the verdict still runs on the closest USDA record.
    if not foods and GTIN_RE.match(query):
        _, off_name = off_product(query)
        if off_name:
            foods = usda_search(off_name)
    food = pick_product(foods, query)
    if food is None:
        return None, None
    image = None
    gtin = (food.get("gtinUpc") or "").strip()
    if gtin:
        image, _ = off_product(gtin)
    return food, image


@app.get("/lookup", response_class=HTMLResponse)
def lookup(q: str = Query(default="")):
    esc = lambda s: html.escape(s or "", quote=True)  # noqa: E731
    query = (q or "").strip()
    if not query:
        return HTMLResponse(lookup_page(None, None, None, PLAY_STORE_URL))
    if not USDA_API_KEY:
        return HTMLResponse(lookup_page(
            query, None,
            "Product search isn't configured on this server yet.",
            PLAY_STORE_URL))
    food, image = lookup_product(query)
    if food is None:
        return HTMLResponse(lookup_page(
            query, None, f'No products found for "{query}".',
            PLAY_STORE_URL))
    brand = food.get("brandName") or food.get("brandOwner") or ""
    verdict = verdict_for(
        food.get("description") or "",
        brand,
        food.get("ingredients") or "",
    )
    return HTMLResponse(lookup_page(query, (verdict, image), None,
                                    PLAY_STORE_URL))
