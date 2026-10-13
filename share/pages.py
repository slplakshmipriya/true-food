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
.btn-disabled{opacity:.55;cursor:default}
nav.top .nav-note{margin-left:18px;color:#6b7280;font-weight:600;font-size:15px}
.hero-note{color:#fff;font-weight:600;font-size:16px}
.card-note{color:#4b5563;font-weight:600;font-size:15px}
.shot{background:#f9fafb;text-align:center}
.app-shot{width:min(300px,72vw);border-radius:28px;
box-shadow:0 24px 60px rgba(0,0,0,.28);border:1px solid #e5e7eb}
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
footer a{color:inherit}
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
.score-note{font-size:13px;color:#6b7280;margin:0 0 10px;max-width:560px}
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


def detect_platform(user_agent):
    """'android' | 'ios' | 'desktop' from a User-Agent header.

    iPadOS 13+ in desktop mode reports a Macintosh UA, so a Macintosh UA
    carrying a Mobile token is treated as iOS (it's an iPad).
    """
    ua = (user_agent or "").lower()
    if "android" in ua:
        return "android"
    if "iphone" in ua or "ipod" in ua or "ipad" in ua:
        return "ios"
    if "macintosh" in ua and "mobile" in ua:
        return "ios"
    return "desktop"


def app_cta(platform, kind, page):
    """Platform-aware app CTA snippet.

    kind: 'nav' (top navigation), 'hero' (landing hero button),
    'cta' (landing bottom section), 'card' (lookup verdict card).
    page: 'landing' | 'lookup' — feeds the src tag.
    Android links via /go/play (counted server-side, then redirected to
    the Play Store); iOS shows a non-link 'coming soon'; desktop shows
    plain text.
    """
    href = "/go/play?src=" + page + "-" + kind
    link_cls = {"nav": "link", "hero": "btn btn-outline",
                "cta": "btn btn-light", "card": "btn btn-green"}[kind]
    text_cls = {"nav": "nav-note", "hero": "hero-note", "cta": "hero-note",
                "card": "card-note"}[kind]
    if platform == "android":
        label = "Get the Android app" if kind == "hero" else "Get the app"
        return "<a class='" + link_cls + "' href='" + href + "'>" + label + "</a>"
    if platform == "ios":
        return ("<span class='" + link_cls + " btn-disabled'>"
                "iOS app coming soon</span>")
    return ("<span class='" + text_cls + "'>"
            "Download the app on your phone</span>")


def landing_page(platform="desktop"):
    return (
        "<!doctype html><html lang='en'><head><meta charset='utf-8' />"
        "<meta name='viewport' content='width=device-width, initial-scale=1' />"
        "<title>BareLabel: Know what's in your food</title>"
        "<meta name='description' content='Search any grocery product. "
        "BareLabel flags the ingredients you would rather avoid and scores "
        "how clean it is.' />"
        "<link rel='canonical' href='https://barelabel.online/' />"
        "<meta property='og:title' "
        "content='BareLabel: Know what&#39;s in your food' />"
        "<meta property='og:description' content='Search any grocery "
        "product. BareLabel flags the ingredients you would rather avoid "
        "and scores how clean it is.' />"
        "<meta property='og:type' content='website' />"
        "<meta property='og:url' content='https://barelabel.online/' />"
        "<meta property='og:image' "
        "content='https://barelabel.online/static/app-screenshot.png' />"
        "<meta name='twitter:card' content='summary_large_image' />"
        "<style>" + SITE_CSS + "</style></head><body>"
        "<div class='wrap'><nav class='top'>"
        "<a class='brand' href='/'>BareLabel</a>"
        "<span><a class='link' href='/lookup'>Try it</a> "
        + app_cta(platform, "nav", "landing") + "</span></nav></div>"
        "<div class='hero'>"
        "<h1>Know what's in your food.</h1>"
        "<p>Search any grocery product. BareLabel flags the ingredients "
        "you'd rather avoid, scores how clean it is, and finds cleaner "
        "alternates.</p>"
        "<a class='btn btn-light' href='/lookup'>Try it on the web</a>"
        + app_cta(platform, "hero", "landing") +
        "</div>"
        "<section class='shot'><div class='wrap'>"
        "<h2>See it in action</h2>"
        "<p class='sub'>Scan a barcode or search a product: BareLabel "
        "screens every ingredient and scores how clean it is.</p>"
        "<img class='app-shot' src='/static/app-screenshot.png' "
        "alt='BareLabel app showing a product search with CLEAN verdicts' "
        "loading='lazy' />"
        "</div></section>"
        "<section><div class='wrap'>"
        "<h2>How it works</h2>"
        "<p class='sub'>Three steps between you and a cleaner cart.</p>"
        "<div class='steps'>"
        "<div class='step'><div class='n'>\U0001f50d</div><h3>Search or scan</h3>"
        "<p>Type a product name or barcode, or scan it in the Android app.</p></div>"
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
        "<span class='flag'>Emulsifiers &amp; Thickening Agents</span>"
        "<span class='flag'>Flavor Enhancers &amp; Additives</span>"
        "<span class='flag'>Fats &amp; Hydrogenated Oils</span>"
        "<span class='flag'>Processed Sweeteners &amp; Fibers</span>"
        "<span class='flag'>Dairy</span><span class='flag'>Gluten</span>"
        "<span class='flag'>Non-Vegetarian</span>"
        "<span class='flag'>Non-Vegan</span>"
        "</div></div></section>"
        "<div class='cta'>"
        "<h2>Take it to the store.</h2>"
        "<p>Barcode scanning, price comparisons, and clean alternates:"
        " in your pocket.</p>"
        + app_cta(platform, "cta", "landing") +
        "</div>"
        "<footer>BareLabel \u00b7 Ingredient data: "
        "<a href='https://fdc.nal.usda.gov/' target='_blank' "
        "rel='noopener'>USDA FoodData Central</a> &amp; "
        "<a href='https://world.openfoodfacts.org/' target='_blank' "
        "rel='noopener'>Open Food Facts</a></footer>"
        "</body></html>"
    )


def lookup_page(query, result, error, platform="desktop"):
    """Search form plus an optional verdict card or error message.

    result is (verdict_dict, image_url_or_None); verdict_dict comes from
    verdict.verdict_for.
    """
    esc = lambda s: html.escape(s or "", quote=True)  # noqa: E731
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
            + "<p class='score-note'>The verdict is binary: one flagged "
              "ingredient is enough for DIRTY. The score rates the whole "
              "ingredient list, 0 to 100.</p>"
            + flagged_html
            + "<p class='note'>Verdict uses BareLabel's default avoid list "
              "(everything it flags). The "
            + {"android": "Android", "ios": "iOS"}.get(platform, "mobile")
            + " app lets you customize yours.</p>"
            + "<p>" + app_cta(platform, "card", "lookup") + "</p></div>"
        )
    title = ("BareLabel lookup: " + q) if query else "BareLabel product lookup"
    return (
        "<!doctype html><html lang='en'><head><meta charset='utf-8' />"
        "<meta name='viewport' content='width=device-width, initial-scale=1' />"
        "<title>" + title + "</title>"
        "<meta name='description' content='Look up any grocery product. "
        "BareLabel checks every ingredient against your avoid list and "
        "scores how clean it is.' />"
        "<link rel='canonical' href='https://barelabel.online/lookup' />"
        "<meta property='og:title' content='" + title + "' />"
        "<meta property='og:description' content='Look up any grocery "
        "product. BareLabel checks every ingredient and scores how clean "
        "it is.' />"
        "<meta property='og:type' content='website' />"
        "<meta property='og:url' content='https://barelabel.online/lookup' />"
        "<meta name='twitter:card' content='summary' />"
        "<style>" + SITE_CSS + "</style></head><body>"
        "<div class='wrap'><nav class='top'>"
        "<a class='brand' href='/'>BareLabel</a>"
        "<span><a class='link' href='/lookup'>Try it</a> "
        + app_cta(platform, "nav", "lookup") + "</span></nav>"
        "<h2 style='margin-top:12px'>Product lookup</h2>"
        "<p class='sub'>Search a product name or paste a barcode number.</p>"
        "<form class='search' action='/lookup' method='get'>"
        "<input name='q' value='" + q + "' "
        "placeholder='e.g. cheez-it or 038000265013' autocomplete='off' />"
        "<button type='submit'>Check</button></form>"
        + body +
        "</div>"
        "<footer>BareLabel \u00b7 Ingredient data: "
        "<a href='https://fdc.nal.usda.gov/' target='_blank' "
        "rel='noopener'>USDA FoodData Central</a> &amp; "
        "<a href='https://world.openfoodfacts.org/' target='_blank' "
        "rel='noopener'>Open Food Facts</a></footer></body></html>"
    )
