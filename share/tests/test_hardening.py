"""Route-level hardening tests: /stats gating and rate limiting."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from starlette.requests import Request

import app as app_module
from ratelimit import RateLimiter


def _req(path="/", extra_headers=None, ip="1.2.3.4"):
    headers = [(b"user-agent", b"test-agent")]
    if extra_headers:
        headers += extra_headers
    return Request({
        "type": "http", "method": "GET", "path": path,
        "headers": headers, "client": (ip, 5000), "query_string": b"",
    })


def test_stats_404_without_or_with_wrong_token():
    orig = app_module.STATS_TOKEN
    app_module.STATS_TOKEN = "s3cret"
    try:
        assert app_module.stats_view(_req("/stats")).status_code == 404
        r = app_module.stats_view(
            _req("/stats", [(b"x-stats-token", b"wrong")]))
        assert r.status_code == 404
    finally:
        app_module.STATS_TOKEN = orig


def test_stats_200_with_right_token():
    orig = app_module.STATS_TOKEN
    app_module.STATS_TOKEN = "s3cret"
    try:
        r = app_module.stats_view(
            _req("/stats", [(b"x-stats-token", b"s3cret")]))
        assert r.status_code == 200
        assert "landing:" in r.body.decode() or r.body.decode() == "{}"
    finally:
        app_module.STATS_TOKEN = orig


def test_stats_404_when_token_unset():
    orig = app_module.STATS_TOKEN
    app_module.STATS_TOKEN = ""
    try:
        r = app_module.stats_view(
            _req("/stats", [(b"x-stats-token", b"anything")]))
        assert r.status_code == 404
    finally:
        app_module.STATS_TOKEN = orig


def test_lookup_rate_limited_after_burst():
    orig = app_module._lookup_limiter
    app_module._lookup_limiter = RateLimiter(max_hits=2, window_secs=60)
    try:
        r1 = app_module.lookup(_req("/lookup"), q="bread")
        r2 = app_module.lookup(_req("/lookup"), q="bread")
        r3 = app_module.lookup(_req("/lookup"), q="bread")
        assert r1.status_code == 200
        assert r2.status_code == 200
        assert r3.status_code == 429
        assert r3.headers.get("retry-after") == "60"
    finally:
        app_module._lookup_limiter = orig


def test_rate_limit_is_per_ip():
    orig = app_module._lookup_limiter
    app_module._lookup_limiter = RateLimiter(max_hits=1, window_secs=60)
    try:
        assert app_module.lookup(_req("/lookup", ip="1.1.1.1"),
                                 q="bread").status_code == 200
        assert app_module.lookup(_req("/lookup", ip="1.1.1.1"),
                                 q="bread").status_code == 429
        assert app_module.lookup(_req("/lookup", ip="2.2.2.2"),
                                 q="bread").status_code == 200
    finally:
        app_module._lookup_limiter = orig


def test_product_page_accepts_request_param():
    r = app_module.product_page(
        _req("/p/038000265013"), gtin="038000265013",
        v="", score=-1, flagged=0,
        n="Test Product", img="https://example.com/x.png", m="")
    assert r.status_code == 200
    assert "Test Product" in r.body.decode()
    assert "javascript:" not in r.body.decode()
