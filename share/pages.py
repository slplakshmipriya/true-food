"""Landing page + lookup page templates for barelabel.online.

Pure HTML builders; app.py supplies the data. No framework, no JS.
"""

import html

SITE_CSS = """
body{font-family:system-ui,-apple-system,sans-serif;margin:0;color:#111827;
background:#ffffff}
.wrap{max-width:960px;margin:0 auto;padding:0 20px}
nav.top{display:flex;justify-content:space-between;align-items:center;
padding:18px 0}
.brand{font-size:22px;font-weight:800;color:#14532d;text-decoration:none}
nav.top a.link{margin-left:18px;color:#374151;text-decoration:none;
font-weight:600;font-size:15px}
.hero{background:linear-gradient(135deg,#14532d,#166534);color:#fff;
text-align:center;padding:72px 20px}
.hero h1{font-size:40px;margin:0 0 12px;line-height:1.15}
.hero p{font-size:18px;opacity:.92;max-width:600px;margin:0 auto 28px}
.btn{display:inline-block;padding:14px 30px;border-radius:12px;font-weight:700;
text-decoration:none;font-size:16px;margin:6px}
.btn-light{background:#fff;color:#14532d}
.btn-outline{border:2px solid #fff;color:#fff}
.btn-green{background:#166534;color:#fff}
section{padding:56px 0}
h2{font-size:28px;text-align:center;margin:0 0 8px}
.sub{text-align:center;color:#6b7280;margin:0 0 32px;font-size:16px}
.steps{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));
gap:20px}
.step{background:#f0fdf4;border-radius:16px;padding:28px 22px;text-align:center}
.step .n{font-size:32px}
.step h3{margin:12px 0 8px;font-size:17px}
.step p{color:#4b5563;font-size:14px;margin:0;line-height:1.5}
.flags{display:flex;flex-wrap:wrap;gap:10px;justify-content:center;
max-width:700px;margin:0 auto}
.flag{background:#fef3c7;color:#92400e;padding:8px 16px;border-radius:999px;
font-size:14px;font-weight:600}
.cta{background:#14532d;color:#fff;text-align:center;padding:56px 20px}
.cta h2{color:#fff}
.cta p{opacity:.9;margin:0 0 24px}
footer{text-align:center;color:#9ca3af;font-size:13px;padding:28px 20px}
form.search{display:flex;gap:10px;max-width:560px;margin:28px auto}
form.search input{flex:1;padding:14px 18px;border:2px solid #d1d5db;
border-radius:12px;font-size:16px}
form.search button{padding:14px 28px;border:none;border-radius:12px;
background:#166534;color:#fff;font-weight:700;font-size:16px;cursor:pointer}
.card{max-width:640px;margin:32px auto;background:#fff;border:1px solid #e5e7eb;
border-radius:20px;padding:32px;box-shadow:0 8px 30px rgba(0,0,0,.06);
text-align:center}
.card img.product{max-width:180px;border-radius:12px}
.badge{display:inline-block;padding:8px 20px;border-radius:999px;
font-weight:800;margin:14px 0 6px;color:#fff}
.badge.clean{background:#166534}.badge.dirty{background:#991b1b}
.score{font-size:15px;color:#4b5563;margin:6px 0 4px}
.cat{color:#6b7280;font-size:14px}
.flagged{text-align:left;margin-top:20px}
.fgroup{margin-bottom:14px}
.fgroup h4{margin:0 0 4px;font-size:15px;color:#991b1b}
.fgroup .why{font-size:13px;color:#6b7280;margin:0 0 6px;line-height:1.5}
.fgroup .terms{font-size:14px;color:#374151}
.note{font-size:13px;color:#9ca3af;margin-top:20px;line-height:1.5}
.error{max-width:560px;margin:32px auto;text-align:center;color:#991b1b;
background:#fef2f2;border-radius:12px;padding:20px}
"""


def landing_page(play_store_url):
    e = html.escape(play_store_url, quote=True)
    return (
        "<!doctype html><html lang='en'><head><meta charset='utf-8' />"
        "<meta name='viewport' content='width=device-width, initial-scale=1' />"
        "<title>BareLabel \u2014 Know what's in your food</title>"
        "<meta name='description' content='Search any grocery product. "
        "BareLabel flags the ingredients you would rather avoid and scores "
        "how clean it is.' />"
        "<style>" + SITE_CSS + "</style></head><body>"
        "<div class='wrap'><nav class='top'>"
        "<a class='brand' href='/'>BareLabel</a>"
        "<span><a class='link' href='/lookup'>Try it</a> "
        "<a class='link' href='" + e + "'>Get the app</a></span></nav></div>"
        "<div class='hero'>"
        "<h1>Know what's in your food.</h1>"
        "<p>Search any grocery product. BareLabel flags the ingredients "
        "you'd rather avoid, scores how clean it is, and finds cleaner "
        "alternates.</p>"
        "<a class='btn btn-light' href='/lookup'>Try it on the web</a>"
        "<a class='btn btn-outline' href='" + e + "'>Get the Android app</a>"
        "</div>"
        "<section><div class='wrap'>"
        "<h2>How it works</h2>"
        "<p class='sub'>Three steps between you and a cleaner cart.</p>"
        "<div class='steps'>"
        "<div class='step'><div class='n'>\U0001f50d</div><h3>Search or scan</h3>"
        "<p>Type a product name or barcode \u2014 or scan it in the Android app.</p></div>"
        "<div class='step'><div class='n'>\u2696\ufe0f</div><h3>Instant verdict</h3>"
        "<p>Every ingredient is checked against your avoid list: CLEAN or DIRTY, "
        "plus a 0\u2013100 Clean Score.</p></div>"
        "<div class='step'><div class='n'>\U0001f6d2</div><h3>Shop clean alternates</h3>"
        "<p>One tap finds cleaner swaps and where to buy them.</p></div>"
        "</div></div></section>"
        "<section style='background:#f9fafb'><div class='wrap'>"
        "<h2>What BareLabel flags</h2>"
        "<p class='sub'>Eleven ingredient categories, curated and explainable.</p>"
        "<div class='flags'>"
        "<span class='flag'>Artificial Colors</span>"
        "<span class='flag'>Preservatives</span>"
        "<span class='flag'>Artificial Sweeteners</span>"
        "<span class='flag'>Emulsifiers &amp; Thickeners</span>"
        "<span class='flag'>Flavor Enhancers</span>"
        "<span class='flag'>Hydrogenated Oils</span>"
        "<span class='flag'>Processed Sweeteners</span>"
        "<span class='flag'>Dairy</span><span class='flag'>Gluten</span>"
        "<span class='flag'>Non-Vegetarian</span>"
        "<span class='flag'>Non-Vegan</span>"
        "</div></div></section>"
        "<div class='cta'>"
        "<h2>Take it to the store.</h2>"
        "<p>Barcode scanning, price comparisons, and clean alternates "
        "\u2014 in your pocket.</p>"
        "<a class='btn btn-light' href='" + e + "'>Get BareLabel for Android</a>"
        "</div>"
        "<footer>BareLabel \u00b7 Ingredient data: USDA FoodData Central "
        "&amp; Open Food Facts</footer>"
        "</body></html>"
    )


def lookup_page(query, result, error, play_store_url):
    """Search form plus an optional verdict card or error message.

    result is (verdict_dict, image_url_or_None); verdict_dict comes from
    verdict.verdict_for.
    """
    esc = lambda s: html.escape(s or "", quote=True)  # noqa: E731
    e = esc(play_store_url)
    q = esc(query)
    body = ""
    if error:
        body = "<div class='error'>" + esc(error) + "</div>"
    elif result:
        verdict, image = result
        badge = ("<div class='badge clean'>\u2713 CLEAN</div>"
                 if verdict["clean"]
                 else "<div class='badge dirty'>\u26a0 DIRTY</div>")
        img = ("<img class='product' src='" + esc(image) + "' alt='' />"
               if image else "")
        brand = ("<div class='cat'>" + esc(verdict["brand"]) + "</div>"
                 if verdict["brand"] else "")
        cat = ("<div class='cat'>" + esc(verdict["category"]) + "</div>"
               if verdict["category"] else "")
        groups = ""
        for g in verdict["categories"]:
            terms = ", ".join(g["ingredients"])
            groups += (
                "<div class='fgroup'><h4>" + esc(g["name"]) + "</h4>"
                "<p class='why'>" + esc(g["reason"]) + "</p>"
                "<div class='terms'>" + esc(terms) + "</div></div>"
            )
        flagged_html = ("<div class='flagged'>" + groups + "</div>"
                        if groups else
                        "<p class='note'>No flagged ingredients.</p>")
        body = (
            "<div class='card'>" + img
            + "<h2>" + esc(verdict["name"]) + "</h2>" + brand + cat + badge
            + "<div class='score'>Clean Score <b>"
            + str(verdict["score"]) + "/100</b></div>"
            + flagged_html
            + "<p class='note'>Verdict uses BareLabel's default avoid list "
              "(everything it flags). The Android app lets you customize "
              "yours.</p>"
            + "<p><a class='btn btn-green' href='" + e + "'>"
              "Get the app</a></p></div>"
        )
    title = ("BareLabel lookup: " + q) if query else "BareLabel product lookup"
    return (
        "<!doctype html><html lang='en'><head><meta charset='utf-8' />"
        "<meta name='viewport' content='width=device-width, initial-scale=1' />"
        "<title>" + title + "</title>"
        "<style>" + SITE_CSS + "</style></head><body>"
        "<div class='wrap'><nav class='top'>"
        "<a class='brand' href='/'>BareLabel</a>"
        "<span><a class='link' href='/lookup'>Try it</a> "
        "<a class='link' href='" + e + "'>Get the app</a></span></nav>"
        "<h2 style='margin-top:12px'>Product lookup</h2>"
        "<p class='sub'>Search a product name or paste a barcode number.</p>"
        "<form class='search' action='/lookup' method='get'>"
        "<input name='q' value='" + q + "' "
        "placeholder='e.g. cheez-it or 038000265013' autocomplete='off' />"
        "<button type='submit'>Check</button></form>"
        + body +
        "</div>"
        "<footer>BareLabel \u00b7 Ingredient data: USDA FoodData Central "
        "&amp; Open Food Facts</footer></body></html>"
    )
