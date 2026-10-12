"""Tests for usda_search retry behavior on transient USDA failures."""
import io
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import app as app_module


def _resp(foods):
    body = json.dumps({"foods": foods}).encode()

    class R:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return body

    return R()


def _patch(key, urlopen):
    orig_key = app_module.USDA_API_KEY
    orig_open = urllib.request.urlopen
    app_module.USDA_API_KEY = key
    urllib.request.urlopen = urlopen
    return orig_key, orig_open


def _restore(orig_key, orig_open):
    app_module.USDA_API_KEY = orig_key
    urllib.request.urlopen = orig_open


def test_usda_search_retries_once_on_transient_failure():
    calls = {"n": 0}

    def flaky(req, timeout=None):
        calls["n"] += 1
        if calls["n"] == 1:
            raise urllib.error.URLError("boom")
        return _resp([{"description": "BREAD"}])

    orig = _patch("test-key", flaky)
    try:
        foods = app_module.usda_search("bread")
    finally:
        _restore(*orig)
    assert foods == [{"description": "BREAD"}]
    assert calls["n"] == 2


def test_usda_search_no_retry_on_403():
    calls = {"n": 0}

    def forbidden(req, timeout=None):
        calls["n"] += 1
        raise urllib.error.HTTPError(
            req.full_url, 403, "Forbidden", {}, io.BytesIO())

    orig = _patch("test-key", forbidden)
    try:
        try:
            app_module.usda_search("bread")
            assert False, "expected UsdaError"
        except app_module.UsdaError:
            pass
    finally:
        _restore(*orig)
    assert calls["n"] == 1  # bad key: retrying is pointless


def test_usda_search_gives_up_after_two_attempts():
    calls = {"n": 0}

    def always_down(req, timeout=None):
        calls["n"] += 1
        raise urllib.error.URLError("down")

    orig = _patch("test-key", always_down)
    try:
        try:
            app_module.usda_search("bread")
            assert False, "expected UsdaError"
        except app_module.UsdaError:
            pass
    finally:
        _restore(*orig)
    assert calls["n"] == 2
