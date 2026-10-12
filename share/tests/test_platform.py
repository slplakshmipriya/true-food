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

PLAY_URL = "https://play.google.com/store/apps/details?id=com.barelabel.app"


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


def test_cta_android_links_to_play_store():
    for kind in ("nav", "hero", "cta", "card"):
        html = app_cta("android", PLAY_URL, kind)
        assert 'href=' in html and PLAY_URL in html, kind
        assert "coming soon" not in html


def test_cta_ios_is_coming_soon_without_link():
    for kind in ("nav", "hero", "cta", "card"):
        html = app_cta("ios", PLAY_URL, kind)
        assert "iOS app coming soon" in html, kind
        assert "href=" not in html, kind


def test_cta_desktop_is_plain_text():
    for kind in ("nav", "hero", "cta", "card"):
        html = app_cta("desktop", PLAY_URL, kind)
        assert "Download the app on your phone" in html, kind
        assert "href=" not in html, kind


def test_landing_page_cta_per_platform():
    android = landing_page(PLAY_URL, "android")
    assert PLAY_URL in android and "Get the Android app" in android
    ios = landing_page(PLAY_URL, "ios")
    assert "iOS app coming soon" in ios
    assert PLAY_URL not in ios
    desktop = landing_page(PLAY_URL, "desktop")
    assert "Download the app on your phone" in desktop
    # Default platform keeps old callers working.
    assert "Download the app on your phone" in landing_page(PLAY_URL)


def test_lookup_page_cta_per_platform():
    android = lookup_page("bread", None, "Some error", PLAY_URL, "android")
    assert PLAY_URL in android
    ios = lookup_page("bread", None, "Some error", PLAY_URL, "ios")
    assert "iOS app coming soon" in ios
    desktop = lookup_page("bread", None, "Some error", PLAY_URL, "desktop")
    assert "Download the app on your phone" in desktop


def test_landing_page_includes_app_screenshot():
    html = landing_page(PLAY_URL, "android")
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
        assert PLAY_URL in android
        ios = app_module.index(req(IPHONE_UA)).body.decode()
        assert "iOS app coming soon" in ios
        desktop = app_module.index(req(DESKTOP_UA)).body.decode()
        assert "Download the app on your phone" in desktop

        lookup_ios = app_module.lookup(req(IPHONE_UA), q="").body.decode()
        assert "iOS app coming soon" in lookup_ios
    finally:
        import importlib
        importlib.reload(app_module)
