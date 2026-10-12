"""Tests for the per-IP sliding-window rate limiter (share/ratelimit.py)."""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from starlette.requests import Request

from ratelimit import RateLimiter, client_ip


def test_allows_up_to_max_then_blocks():
    rl = RateLimiter(max_hits=3, window_secs=60)
    assert rl.allow("a")
    assert rl.allow("a")
    assert rl.allow("a")
    assert not rl.allow("a")


def test_window_expiry_resets_bucket():
    rl = RateLimiter(max_hits=1, window_secs=0.05)
    assert rl.allow("a")
    assert not rl.allow("a")
    time.sleep(0.06)
    assert rl.allow("a")


def test_buckets_are_per_key():
    rl = RateLimiter(max_hits=1, window_secs=60)
    assert rl.allow("a")
    assert not rl.allow("a")
    assert rl.allow("b")


def _req(headers):
    return Request({
        "type": "http", "method": "GET", "path": "/",
        "headers": headers, "client": ("10.0.0.1", 5000),
    })


def test_client_ip_prefers_forwarded_for():
    r = _req([(b"x-forwarded-for", b"9.9.9.9, 1.1.1.1")])
    assert client_ip(r) == "9.9.9.9"


def test_client_ip_falls_back_to_socket():
    r = _req([])
    assert client_ip(r) == "10.0.0.1"
