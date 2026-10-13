"""Tests for the container-local lookup LRU cache (share/cache.py)
and its wiring into the /lookup route.
"""

import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cache import LruCache, cache_key
from starlette.requests import Request


def _req(user_agent="test-agent"):
    return Request({
        "type": "http",
        "method": "GET",
        "path": "/lookup",
        "headers": [(b"user-agent", user_agent.encode())],
    })


def test_round_trip():
    c = LruCache()
    c.put("bread", {"food": {"description": "BREAD"}, "image": "http://img"})
    hit = c.get("bread")
    assert hit == {"food": {"description": "BREAD"}, "image": "http://img"}
    assert len(c) == 1


def test_miss_returns_none():
    assert LruCache().get("nope") is None


def test_ttl_expiry():
    c = LruCache(ttl_seconds=0)  # expires immediately
    c.put("k", {"v": 1})
    assert c.get("k") is None
    assert len(c) == 0  # expired entries are dropped on read


def test_lru_eviction_by_bytes():
    c = LruCache(ttl_seconds=3600, max_bytes=80)
    c.put("a", {"v": "x" * 20})  # ~30 bytes
    c.put("b", {"v": "y" * 20})  # ~30 bytes
    c.put("c", {"v": "z" * 20})  # ~30 bytes -> pushes total over 100
    assert c.get("a") is None  # oldest evicted
    assert c.get("b") is not None
    assert c.get("c") is not None
    assert c.total_bytes() <= 80


def test_lru_touch_refreshes_recency():
    c = LruCache(ttl_seconds=3600, max_bytes=80)
    c.put("a", {"v": "x" * 20})
    c.put("b", {"v": "y" * 20})
    assert c.get("a") is not None  # touch "a", now "b" is LRU
    c.put("c", {"v": "z" * 20})
    assert c.get("b") is None
    assert c.get("a") is not None


def test_oversized_single_entry_not_cached():
    c = LruCache(ttl_seconds=3600, max_bytes=10)
    c.put("big", {"v": "x" * 100})
    assert c.get("big") is None
    assert len(c) == 0


def test_overwrite_same_key():
    c = LruCache(ttl_seconds=3600, max_bytes=1000)
    c.put("k", {"v": "x" * 20})
    before = c.total_bytes()
    c.put("k", {"v": "y" * 20})
    assert len(c) == 1
    assert c.total_bytes() == before  # replaced, not double-counted
    assert c.get("k") == {"v": "y" * 20}


def test_thread_safety_smoke():
    c = LruCache(ttl_seconds=3600, max_bytes=10 * 1024 * 1024)
    errors = []

    def worker(n):
        try:
            for i in range(200):
                k = f"k{(n + i) % 50}"
                c.put(k, {"v": i})
                c.get(k)
        except Exception as e:  # noqa: BLE001
            errors.append(e)

    threads = [threading.Thread(target=worker, args=(n,)) for n in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors
    assert c.total_bytes() <= 10 * 1024 * 1024


def test_cache_key_normalization():
    assert cache_key("  Cheez-It ") == "cheez-it"
    assert cache_key("CHEEZ-IT") == "cheez-it"
    # Barcode queries ignore leading zeros, like pick_product's matching.
    assert cache_key("038000265013") == cache_key("38000265013")
    assert cache_key("") == ""


def test_lookup_route_uses_cache():
    import app as app_module

    calls = {"usda": 0, "off": 0}

    def fake_usda_search(query, page_size=25):
        calls["usda"] += 1
        return [{
            "description": "Cache Test Bread",
            "brandName": "Test",
            "brandOwner": "Test",
            "ingredients": "flour, water",
            "gtinUpc": "000000000001",
        }]

    def fake_off_product(gtin):
        calls["off"] += 1
        return ("http://img/test.jpg", "Cache Test Bread")

    app_module.usda_search = fake_usda_search
    app_module.off_product = fake_off_product
    app_module.USDA_API_KEY = "test-key"
    try:
        first = app_module.lookup(_req(), q="cache-test-bread-xyz")
        second = app_module.lookup(_req(), q="  CACHE-TEST-BREAD-XYZ ")  # key normalizes
        assert calls["usda"] == 1  # second lookup served from cache
        # Image is fetched at render time for the cleanest pick, once per
        # page view (not part of the cached record).
        assert calls["off"] == 2
        body1 = first.body.decode()
        body2 = second.body.decode()
        # Same verdict rendered both times (page echoes the raw query,
        # so bodies differ in the search box text only).
        assert "Cache Test Bread" in body1
        assert "Cache Test Bread" in body2
    finally:
        # Restore real functions for other tests / server runs.
        import importlib
        importlib.reload(app_module)


def test_lookup_route_does_not_cache_misses():
    import app as app_module

    calls = {"usda": 0}

    def fake_usda_search(query, page_size=25):
        calls["usda"] += 1
        return []

    app_module.usda_search = fake_usda_search
    app_module.USDA_API_KEY = "test-key"
    try:
        app_module.lookup(_req(), q="cache-test-miss-xyz")
        app_module.lookup(_req(), q="cache-test-miss-xyz")
        assert calls["usda"] == 2  # misses are never cached
    finally:
        import importlib
        importlib.reload(app_module)
