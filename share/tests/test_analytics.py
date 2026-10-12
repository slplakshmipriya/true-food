"""Tests for GA4 Measurement Protocol analytics (share/analytics.py)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from starlette.requests import Request

import analytics
import app as app_module


def _req(user_agent="test-agent", ip="1.2.3.4"):
    return Request({
        "type": "http",
        "method": "GET",
        "path": "/",
        "headers": [(b"user-agent", user_agent.encode()),
                    (b"x-forwarded-for", ip.encode())],
    })


class _SyncThread:
    """Run the fire-and-forget sender inline so tests stay deterministic."""

    def __init__(self, target, args=(), daemon=None):
        self._target, self._args = target, args

    def start(self):
        self._target(*self._args)


def _capture():
    """Patch analytics to capture payloads instead of hitting Google."""
    sent = []
    orig = (analytics.MEASUREMENT_ID, analytics.API_SECRET,
            analytics._send, analytics.threading.Thread)
    analytics.MEASUREMENT_ID = "G-TEST123"
    analytics.API_SECRET = "test-secret"
    analytics._send = sent.append
    analytics.threading.Thread = _SyncThread
    return sent, orig


def _restore(orig):
    (analytics.MEASUREMENT_ID, analytics.API_SECRET,
     analytics._send, analytics.threading.Thread) = orig


def test_track_noop_when_unconfigured():
    # Fresh import state has no IDs; _send must never fire.
    assert not analytics.configured()
    orig_send = analytics._send

    def boom(payload):
        raise AssertionError("must not send when unconfigured")

    analytics._send = boom
    try:
        analytics.track("landing_view", {"platform": "android"}, "cid")
    finally:
        analytics._send = orig_send


def test_track_builds_valid_mp_payload():
    sent, orig = _capture()
    try:
        analytics.track("app_click",
                        {"platform": "android", "placement": "landing-hero"},
                        "client-1")
    finally:
        _restore(orig)
    assert len(sent) == 1
    p = sent[0]
    assert p["client_id"] == "client-1"
    assert len(p["events"]) == 1
    ev = p["events"][0]
    assert ev["name"] == "app_click"
    assert ev["params"] == {"platform": "android",
                           "placement": "landing-hero"}


def test_client_id_stable_per_visitor_per_day():
    a1 = analytics.client_id_for(_req("ua-a", "1.2.3.4"))
    a2 = analytics.client_id_for(_req("ua-a", "1.2.3.4"))
    b = analytics.client_id_for(_req("ua-b", "1.2.3.4"))
    assert a1 == a2 and len(a1) == 32
    assert a1 != b
    # Raw IP never appears in the id.
    assert "1.2.3.4" not in a1


def test_landing_view_sends_ga_event():
    sent, orig = _capture()
    try:
        app_module.index(_req("android-ua", "9.9.9.9"))
    finally:
        _restore(orig)
    assert len(sent) == 1
    ev = sent[0]["events"][0]
    assert ev["name"] == "landing_view"
    assert ev["params"] == {"platform": "android"}
    assert sent[0]["client_id"] == \
        analytics.client_id_for(_req("android-ua", "9.9.9.9"))


def test_search_and_click_send_ga_events():
    sent, orig = _capture()
    orig_lookup = app_module.lookup_product
    app_module.lookup_product = lambda q: (None, None)
    try:
        app_module.lookup(_req("ua", "5.6.7.8"), q="bread")
        app_module.go_play(_req("ua", "5.6.7.8"), src="lookup-card")
    finally:
        app_module.lookup_product = orig_lookup
        _restore(orig)
    names = [p["events"][0]["name"] for p in sent]
    assert names == ["search", "app_click"]
    assert sent[0]["events"][0]["params"] == {"search_type": "text"}
    assert sent[1]["events"][0]["params"] == {"platform": "desktop",
                                             "placement": "lookup-card"}
