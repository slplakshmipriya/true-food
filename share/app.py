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

import hmac
import html
import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

from fastapi import FastAPI, Query, Request
from fastapi.responses import (
    HTMLResponse, JSONResponse, RedirectResponse, Response,
)
from fastapi.staticfiles import StaticFiles

from pages import detect_platform, landing_page, lookup_page
from cache import LruCache, cache_key
from stats import stats
from analytics import client_id_for, track
from ratelimit import RateLimiter, client_ip
from security import (
    MAX_QUERY_LEN,
    SECURITY_HEADERS,
    sanitize_gtin,
    sanitize_image_url,
)
from verdict import (
    ranked_candidates,
    select_top_pick,
    verdict_for,
)

app = FastAPI(title="barelabel-share")

PLAY_STORE_URL = (
    "https://play.google.com/store/apps/details?id=com.barelabel.app"
)
SHARE_HOST = "barelabel.online"
APP_PACKAGE = "com.barelabel.app"
GTIN_RE = re.compile(r"^[0-9]{8,14}$")

ASSETLINKS_PATH = Path(__file__).with_name("assetlinks.json")
STATIC_DIR = Path(__file__).with_name("static")

app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


def rate_limited(request, limiter):
    """429 response when the client is over the limit, else None."""
    if limiter.allow(client_ip(request)):
        return None
    return HTMLResponse(
        "<h1>Too many requests</h1>"
        "<p>Please slow down and try again in a minute.</p>",
        status_code=429,
        headers={"Retry-After": "60"},
    )


@app.middleware("http")
async def security_headers(request, call_next):
    """Cheap hardening: MIME-sniffing off, no framing, no referrer leak.

    Deliberately no Content-Security-Policy: the pages are zero-JS with
    inline styles and images from arbitrary https hosts, so a wrong CSP
    breaks rendering while adding little here.
    """
    response = await call_next(request)
    for header, value in SECURITY_HEADERS.items():
        response.headers[header] = value
    return response

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
_OFF_LOCK = threading.Lock()

# Upstream-fanout routes get strict limits (each lookup = several USDA/OFF
# calls); cheap pages get a looser one. Container-local, like the cache.
_lookup_limiter = RateLimiter(max_hits=30, window_secs=60)
_page_limiter = RateLimiter(max_hits=120, window_secs=60)

# Shared secret for /stats (set STATS_TOKEN env var; unset = 404 always).
STATS_TOKEN = os.environ.get("STATS_TOKEN", "")


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
    with _OFF_LOCK:
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
    with _OFF_LOCK:
        if len(_OFF_CACHE) > 2000:
            _OFF_CACHE.clear()
        _OFF_CACHE[gtin] = (now, image, name)
    return image, name


@app.get("/go/play")
def go_play(request: Request, src: str = Query(default="")):
    """Counted Play Store redirect: every 'get the app' tap lands here
    first (zero-JS click tracking), then 302s to the Play Store."""
    platform = detect_platform(request.headers.get("user-agent"))
    placement = src or "unknown"
    stats.incr(f"click:{platform}:{placement}")
    track("app_click", {"platform": platform, "placement": placement},
          client_id_for(request))
    return RedirectResponse(PLAY_STORE_URL)


@app.get("/stats")
def stats_view(request: Request):
    """Analytics snapshot as JSON. Gated by the STATS_TOKEN env var
    (X-Stats-Token header); 404 when unset or wrong. Container-local:
    resets on cold start / new revisions, per-instance counts."""
    token = request.headers.get("x-stats-token", "")
    if not STATS_TOKEN or not hmac.compare_digest(token, STATS_TOKEN):
        return JSONResponse({"error": "not found"}, status_code=404)
    return JSONResponse(stats.snapshot())


@app.get("/robots.txt", response_class=Response)
def robots_txt():
    return Response(
        "User-agent: *\nAllow: /\n"
        f"Sitemap: https://{SHARE_HOST}/sitemap.xml\n",
        media_type="text/plain",
    )


@app.get("/sitemap.xml", response_class=Response)
def sitemap_xml():
    urls = "\n".join(
        f"  <url><loc>https://{SHARE_HOST}{path}</loc>"
        f"<changefreq>weekly</changefreq><priority>{prio}</priority></url>"
        for path, prio in [("/", "1.0"), ("/lookup", "0.8")]
    )
    return Response(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        f"{urls}\n</urlset>",
        media_type="application/xml",
    )


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
    request: Request,
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
    limited = rate_limited(request, _page_limiter)
    if limited is not None:
        return limited

    verdict = "clean" if v == "clean" else ("dirty" if v == "dirty" else "")
    esc = lambda s: html.escape(s, quote=True)  # noqa: E731

    # The app omits img=/n= when Open Food Facts can supply them (keeps
    # shared URLs short); fill in from OFF here. Skip the fetch entirely
    # when the app sent both.
    off_image, off_name = (None, None)
    if not img.strip() or not n.strip():
        off_image, off_name = off_product(gtin)
    name = (n.strip() or off_name or "A grocery product")[:MAX_QUERY_LEN]

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
    # The app-supplied img= param is scheme-allowlisted: javascript:/data:
    # URLs never reach the <img> tag.
    image = sanitize_image_url(img) or off_image or ""
    og_image = (
        f'<meta property="og:image" content="{esc(image)}" />' if image else ""
    )
    img_tag = (
        f"<img class='product' src='{esc(image)}' alt='' />" if image else ""
    )

    # Retailer chips from the app's merchant names (m=a,b,c).
    merchants = [x.strip()[:MAX_QUERY_LEN] for x in m.split(",") if x.strip()][:6]
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
<meta name="twitter:card" content="summary_large_image" />
<link rel="canonical" href="{esc(canonical)}" />
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
<a class="btn secondary" href="/go/play?src=share">Get the app</a>
</p>
</body>
</html>"""
    return HTMLResponse(page)


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    limited = rate_limited(request, _page_limiter)
    if limited is not None:
        return limited
    platform = detect_platform(request.headers.get("user-agent"))
    stats.incr(f"landing:{platform}")
    track("landing_view", {"platform": platform}, client_id_for(request))
    return HTMLResponse(landing_page(platform))


# ---------------------------------------------------------------------------
# Web lookup: product search + verdict, powered by the ported verdict engine.
# Needs USDA_API_KEY in the environment (set on the Cloud Run service, not
# in the repo — the same key the Android app bakes into BuildConfig).
# ---------------------------------------------------------------------------

USDA_API_KEY = os.environ.get("USDA_API_KEY", "")
USDA_SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search"

# Container-local lookup cache: query -> (USDA food record, OFF image URL).
# 30-day TTL, 100 MB budget, LRU eviction. Per-instance memory only.
_lookup_cache = LruCache()


class UsdaError(Exception):
    """USDA request failed (bad key, rate limit, outage) — not 'no results'."""
    pass


def usda_search(query, page_size=25):
    """Branded USDA search; returns the foods list (possibly empty).

    pageSize 25 mirrors the Android app so the spam filter has a real
    pool to rank. Raises UsdaError when the request itself fails, so
    callers can tell 'USDA is broken' apart from 'no products matched'.
    One retry on transient failures (timeout, 429, 5xx); a 403 means
    the key itself is bad, so retrying is pointless.
    """
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
    err = None
    for _ in range(2):
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                foods = json.loads(resp.read().decode("utf-8")).get("foods") or []
                stats.incr("usda:ok")
                return foods
        except urllib.error.HTTPError as e:
            # 403 = invalid key, 429 = rate limited, 5xx = USDA down.
            # Status goes to the server log only; never echo the key.
            print(f"USDA search failed: HTTP {e.code} for query={query!r}")
            if e.code == 403:
                raise UsdaError(f"HTTP {e.code}")
            err = UsdaError(f"HTTP {e.code}")
        except Exception as e:
            print(f"USDA search failed: {type(e).__name__} for query={query!r}")
            err = UsdaError("request failed")
        time.sleep(1)
    stats.incr("usda:fail")
    track("usda_failure")
    raise err


def pick_product(foods, query):
    """Mirror the app: exact GTIN match, else the ranked top pick."""
    if not foods:
        return None
    if GTIN_RE.match(query or ""):
        needle = query.lstrip("0")
        for f in foods:
            stored = (f.get("gtinUpc") or "").lstrip("0")
            if stored and stored == needle:
                return f
    return select_top_pick(foods, ranked_candidates(foods, query))


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
    gtin = sanitize_gtin(food.get("gtinUpc"))
    if gtin:
        image, _ = off_product(gtin)
    return food, image


@app.get("/lookup", response_class=HTMLResponse)
def lookup(request: Request, q: str = Query(default="")):
    limited = rate_limited(request, _lookup_limiter)
    if limited is not None:
        return limited
    esc = lambda s: html.escape(s or "", quote=True)  # noqa: E731
    query = (q or "").strip()[:MAX_QUERY_LEN]
    platform = detect_platform(request.headers.get("user-agent"))
    if not query:
        return HTMLResponse(lookup_page(None, None, None, platform))
    is_barcode = bool(GTIN_RE.match(query))
    stats.incr("search:barcode" if is_barcode else "search:text")
    track("search", {"search_type": "barcode" if is_barcode else "text"},
          client_id_for(request))
    if not USDA_API_KEY:
        return HTMLResponse(lookup_page(
            query, None,
            "Product search isn't configured on this server yet.",
            platform))
    key = cache_key(query)
    cached = _lookup_cache.get(key)
    if cached is not None:
        food, image = cached["food"], cached["image"]
    else:
        food, image = None, None
        try:
            food, image = lookup_product(query)
        except UsdaError:
            return HTMLResponse(lookup_page(
                query, None,
                "Product search is temporarily unavailable — please try again.",
                platform))
        if food is not None:
            # Cache the USDA record + image URL (never misses or errors).
            _lookup_cache.put(key, {"food": food, "image": image})
    if food is None:
        return HTMLResponse(lookup_page(
            query, None, f'No products found for "{query}".',
            platform))
    brand = food.get("brandName") or food.get("brandOwner") or ""
    verdict = verdict_for(
        food.get("description") or "",
        brand,
        food.get("ingredients") or "",
    )
    return HTMLResponse(lookup_page(query, (verdict, image), None,
                                    platform))
