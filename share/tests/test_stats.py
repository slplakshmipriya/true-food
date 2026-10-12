"""Tests for zero-JS server-side analytics (share/stats.py + wiring)."""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from starlette.requests import Request

import app as app_module
from stats import Stats, stats

ANDROID_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
)
IPHONE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148"
)


def _req(user_agent="test-agent"):
    return Request({
        "type": "http",
        "method": "GET",
        "path": "/",
        "headers": [(b"user-agent", user_agent.encode())],
    })


def test_stats_counter_isolated():
    s = Stats()
    s.incr("a")
    s.incr("a")
    s.incr("b")
    assert s.snapshot() == {"a": 2, "b": 1}
    s.reset()
    assert s.snapshot() == {}


def test_landing_view_counted_by_platform():
    stats.reset()
    app_module.index(_req(ANDROID_UA))
    app_module.index(_req(IPHONE_UA))
    app_module.index(_req(ANDROID_UA))
    snap = stats.snapshot()
    assert snap.get("landing:android") == 2
    assert snap.get("landing:ios") == 1


def test_search_counted_text_vs_barcode():
    stats.reset()
    orig = app_module.lookup_product
    app_module.lookup_product = lambda q: (None, None)  # no results, no network
    try:
        app_module.lookup(_req(), q="bread")
        app_module.lookup(_req(), q="038000265013")
        app_module.lookup(_req(), q="   ")  # blank: not a search
    finally:
        app_module.lookup_product = orig
    snap = stats.snapshot()
    assert snap.get("search:text") == 1
    assert snap.get("search:barcode") == 1
    assert {k for k in snap if k.startswith("search:")} == \
        {"search:text", "search:barcode"}


def test_usda_failure_counted():
    # Real usda_search against a dead endpoint: exercises the retry loop,
    # the usda:fail counter, and the route's error page end to end.
    stats.reset()

    def down(req, timeout=None):
        raise urllib.error.URLError("down")

    orig_open = urllib.request.urlopen
    orig_key = app_module.USDA_API_KEY
    urllib.request.urlopen = down
    app_module.USDA_API_KEY = "test-key"
    try:
        resp = app_module.lookup(_req(), q="bread")
    finally:
        urllib.request.urlopen = orig_open
        app_module.USDA_API_KEY = orig_key
    assert "temporarily unavailable" in resp.body.decode()
    assert stats.snapshot().get("usda:fail") == 1


def test_go_play_redirects_and_counts_click():
    stats.reset()
    resp = app_module.go_play(_req(ANDROID_UA), src="landing-hero")
    assert resp.status_code in (301, 302, 303, 307, 308)
    assert resp.headers["location"] == app_module.PLAY_STORE_URL
    assert stats.snapshot().get("click:android:landing-hero") == 1
    # iOS/desktop taps are counted too if they ever hit the URL directly.
    app_module.go_play(_req(IPHONE_UA), src="share")
    assert stats.snapshot().get("click:ios:share") == 1


def test_stats_endpoint_returns_snapshot():
    from starlette.requests import Request
    stats.reset()
    stats.incr("landing:desktop")
    orig = app_module.STATS_TOKEN
    app_module.STATS_TOKEN = "s3cret"
    try:
        req = Request({
            "type": "http", "method": "GET", "path": "/stats",
            "headers": [(b"x-stats-token", b"s3cret")],
        })
        body = json.loads(app_module.stats_view(req).body.decode())
    finally:
        app_module.STATS_TOKEN = orig
    assert body == {"landing:desktop": 1}
