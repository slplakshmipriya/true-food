"""Google Analytics 4 via Measurement Protocol — server-side, zero JS.

Events are POSTed to GA4 from the server in a fire-and-forget daemon
thread, so a slow or unreachable Google never delays a response. Without
GA_MEASUREMENT_ID and GA_API_SECRET in the environment, track() is a
no-op and the local stats.py counters are the only record.

Privacy posture (deliberate):
- No client JavaScript, no cookies, nothing runs in the visitor's browser.
- client_id is a daily SHA-256 of IP + User-Agent (pseudonymous, rotates
  daily). The raw IP is never logged, stored, or sent to Google.
- Search query text is never sent — only the search type (text/barcode).
- Google's servers see our Cloud Run egress IP, not the visitor's.
"""
import datetime
import hashlib
import json
import os
import threading
import urllib.request
import uuid

MEASUREMENT_ID = os.environ.get("GA_MEASUREMENT_ID", "")
API_SECRET = os.environ.get("GA_API_SECRET", "")
_MP_URL = "https://www.google-analytics.com/mp/collect"

# Cap concurrent outbound GA connections so a traffic flood can't pile up
# threads faster than the 5s urlopen timeout drains them.
_GA_SEMAPHORE = threading.Semaphore(20)


def configured():
    return bool(MEASUREMENT_ID and API_SECRET)


def client_id_for(request):
    """Pseudonymous per-visitor-per-day id. Never stored anywhere."""
    fwd = request.headers.get("x-forwarded-for", "")
    ip = fwd.split(",")[0].strip() if fwd else ""
    ua = request.headers.get("user-agent", "")
    day = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    return hashlib.sha256(f"{ip}|{ua}|{day}|barelabel-ga".encode()).hexdigest()[:32]


def _send(payload):
    # Bound concurrent GA sends; drop (don't queue) under extreme load.
    if not _GA_SEMAPHORE.acquire(blocking=False):
        return
    try:
        req = urllib.request.Request(
            f"{_MP_URL}?measurement_id={MEASUREMENT_ID}&api_secret={API_SECRET}",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            urllib.request.urlopen(req, timeout=5)
        except Exception as e:  # analytics must never break a real request
            print(f"GA event send failed: {type(e).__name__}")
    finally:
        _GA_SEMAPHORE.release()


def _payload(event_name, params, client_id):
    return {
        "client_id": client_id or uuid.uuid4().hex,
        "events": [{"name": event_name, "params": params or {}}],
    }


def track(event_name, params=None, client_id=None):
    """Fire-and-forget GA4 event. No-op unless configured."""
    if not configured():
        return
    payload = _payload(event_name, params, client_id)
    threading.Thread(target=_send, args=(payload,), daemon=True).start()
