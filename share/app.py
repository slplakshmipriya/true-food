"""BareLabel share backend (Cloud Run).

Serves two things:
  /.well-known/assetlinks.json  App Links verification for barelabel.app
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
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

from fastapi import FastAPI, Query
from fastapi.responses import HTMLResponse, JSONResponse

app = FastAPI(title="barelabel-share")

PLAY_STORE_URL = (
    "https://play.google.com/store/apps/details?id=com.example.barelabel"
)
SHARE_HOST = "barelabel.app"
APP_PACKAGE = "com.example.barelabel"
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

# GTIN -> (fetched_at_epoch, image_url|None). 24h TTL; tiny so unbounded
# growth is not a concern, still capped defensively.
_OFF_IMAGE_CACHE: dict[str, tuple[float, str | None]] = {}
_OFF_IMAGE_TTL = 24 * 3600


def merchant_link(merchant: str, product_name: str) -> str:
    """Site-search URL for buying the product at the merchant."""
    tmpl = MERCHANT_SEARCH.get(merchant.strip().lower())
    if tmpl:
        return tmpl.format(q=urllib.parse.quote_plus(product_name))
    q = urllib.parse.quote_plus(f"{merchant} {product_name}")
    return f"https://www.google.com/search?tbm=shop&q={q}"


def off_image_url(gtin: str) -> str | None:
    """Product image from Open Food Facts (free, keyless), cached 24h."""
    now = time.time()
    hit = _OFF_IMAGE_CACHE.get(gtin)
    if hit is not None and now - hit[0] < _OFF_IMAGE_TTL:
        return hit[1]
    url: str | None = None
    try:
        req = urllib.request.Request(
            f"https://world.openfoodfacts.org/api/v0/product/{gtin}.json",
            headers={"User-Agent": "BareLabel/1.0 (share preview)"},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        product = data.get("product") or {}
        url = product.get("image_front_url") or product.get("image_url") or None
    except Exception:
        url = None
    if len(_OFF_IMAGE_CACHE) > 2000:
        _OFF_IMAGE_CACHE.clear()
    _OFF_IMAGE_CACHE[gtin] = (now, url)
    return url


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
    name = n.strip() or "A grocery product"
    esc = lambda s: html.escape(s, quote=True)  # noqa: E731

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
    image = img.strip() or off_image_url(gtin) or ""
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
    return HTMLResponse(
        "<h1>BareLabel share backend</h1>"
        "<p>Serves App Links verification and product link previews.</p>"
    )
