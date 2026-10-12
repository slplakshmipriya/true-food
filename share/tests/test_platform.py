"""Tests for platform detection and the platform-aware app CTA."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pages import app_cta, detect_platform, landing_page, lookup_page

ANDROID_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
)
IPHONE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 "
    "Safari/604.1"
)
IPAD_UA = (
    "Mozilla/5.0 (iPad; CPU OS 17_2 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
)
# iPadOS 13+ in desktop mode: Macintosh UA with a Mobile token.
IPADOS_DESKTOP_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
)
DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)
MAC_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)



def test_detect_platform():
    assert detect_platform(ANDROID_UA) == "android"
    assert detect_platform("mozilla/5.0 (linux; ANDROID 10)") == "android"
    assert detect_platform(IPHONE_UA) == "ios"
    assert detect_platform(IPAD_UA) == "ios"
    assert detect_platform(IPADOS_DESKTOP_UA) == "ios"
    assert detect_platform(DESKTOP_UA) == "desktop"
    assert detect_platform(MAC_UA) == "desktop"
    assert detect_platform("") == "desktop"
    assert detect_platform(None) == "desktop"


def test_cta_android_links_via_counted_redirect():
    for kind in ("nav", "hero", "cta", "card"):
        html = app_cta("android", kind, "landing")
        assert "/go/play?src=landing-" + kind in html, kind
        assert "coming soon" not in html


def test_cta_ios_is_coming_soon_without_link():
    for kind in ("nav", "hero", "cta", "card"):
        html = app_cta("ios", kind, "landing")
        assert "iOS app coming soon" in html, kind
        assert "href=" not in html, kind


def test_cta_desktop_is_plain_text():
    for kind in ("nav", "hero", "cta", "card"):
        html = app_cta("desktop", kind, "landing")
        assert "Download the app on your phone" in html, kind
        assert "href=" not in html, kind


def test_landing_page_cta_per_platform():
    android = landing_page("android")
    assert "/go/play?src=landing-hero" in android
    assert "Get the Android app" in android
    ios = landing_page("ios")
    assert "iOS app coming soon" in ios
    assert "/go/play" not in ios
    desktop = landing_page("desktop")
    assert "Download the app on your phone" in desktop
    assert "/go/play" not in desktop
    # Default platform keeps old callers working.
    assert "Download the app on your phone" in landing_page()


def test_lookup_page_cta_per_platform():
    android = lookup_page("bread", None, "Some error", "android")
    assert "/go/play?src=lookup-nav" in android
    ios = lookup_page("bread", None, "Some error", "ios")
    assert "iOS app coming soon" in ios
    desktop = lookup_page("bread", None, "Some error", "desktop")
    assert "Download the app on your phone" in desktop


def test_landing_pills_use_canonical_category_names():
    html = landing_page("desktop")
    for name in ["Emulsifiers &amp; Thickening Agents",
                 "Flavor Enhancers &amp; Additives",
                 "Fats &amp; Hydrogenated Oils",
                 "Processed Sweeteners &amp; Fibers"]:
        assert name in html


def test_footer_links_data_sources():
    html = landing_page("desktop")
    assert "https://fdc.nal.usda.gov/" in html
    assert "https://world.openfoodfacts.org/" in html


def test_verdict_card_explains_binary_verdict_vs_score():
    from verdict import verdict_for
    v = verdict_for("BREAD", "TestBrand", "wheat flour, water, salt")
    assert not v["clean"]
    html = lookup_page("bread", (v, None), None, "desktop")
    assert "score-note" in html
    assert "binary" in html


def test_landing_page_includes_app_screenshot():
    html = landing_page("android")
    assert "/static/app-screenshot.png" in html
    assert "See it in action" in html


def test_screenshot_asset_exists():
    shot = Path(__file__).resolve().parent.parent / "static" / "app-screenshot.png"
    assert shot.is_file(), "share/static/app-screenshot.png missing"
    assert shot.stat().st_size < 1024 * 1024  # keep it light for the image


def test_route_passes_platform_through():
    import app as app_module
    from starlette.requests import Request

    def req(ua):
        return Request({"type": "http", "method": "GET", "path": "/",
                        "headers": [(b"user-agent", ua.encode())]})

    app_module.USDA_API_KEY = "test-key"
    try:
        android = app_module.index(req(ANDROID_UA)).body.decode()
        assert "/go/play?src=landing-hero" in android
        ios = app_module.index(req(IPHONE_UA)).body.decode()
        assert "iOS app coming soon" in ios
        desktop = app_module.index(req(DESKTOP_UA)).body.decode()
        assert "Download the app on your phone" in desktop

        lookup_ios = app_module.lookup(req(IPHONE_UA), q="").body.decode()
        assert "iOS app coming soon" in lookup_ios
    finally:
        import importlib
        importlib.reload(app_module)
