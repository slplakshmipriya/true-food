"""BareLabel share backend (Cloud Run).

Serves two things:
  /.well-known/assetlinks.json  App Links verification for barelabel.app
  /p/{gtin}                     Open Graph preview page for shared products

The /p/{gtin} page is what chat apps (WhatsApp, Messages, ...) unfurl when a
user shares a product link. Product facts arrive as query params from the app
(v=clean|dirty, score=, flagged=, n=name, img=image URL) so this service needs
no API keys. Tapping "Open in BareLabel" on the page fires the verified App
Link and opens the product in the app; without the app it goes to the Play
Store listing.
"""

import html
import json
import re
from pathlib import Path

from fastapi import FastAPI, Query
from fastapi.responses import HTMLResponse, JSONResponse

app = FastAPI(title="barelabel-share")

PLAY_STORE_URL = (
    "https://play.google.com/store/apps/details?id=com.example.barelabel"
)
GTIN_RE = re.compile(r"^[0-9]{8,14}$")

ASSETLINKS_PATH = Path(__file__).with_name("assetlinks.json")


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

    canonical = f"https://barelabel.app/p/{gtin}"
    og_image = (
        f'<meta property="og:image" content="{esc(img)}" />' if img.strip() else ""
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
</style>
</head>
<body>
{"<img class='product' src='" + esc(img) + "' alt='' />" if img.strip() else ""}
<h1>{esc(name)}</h1>
<div class="badge">{esc(badge)}</div>
<p>{esc(description)}</p>
<p>
<a class="btn primary" href="{esc(canonical)}">Open in BareLabel</a><br />
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
