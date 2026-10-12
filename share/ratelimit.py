"""Per-IP sliding-window rate limiter (container-local, thread-safe).

Protects the upstream-fanout routes (/lookup, /p/{gtin}) from floods that
would burn the USDA quota or pile up outbound connections. Counts reset
on cold start like the rest of the container-local state.
"""
import threading
import time
from collections import deque


class RateLimiter:
    def __init__(self, max_hits=30, window_secs=60):
        self._max = max_hits
        self._window = window_secs
        self._lock = threading.Lock()
        self._buckets = {}

    def allow(self, key):
        """True if the request may proceed; False if over the limit."""
        now = time.time()
        cutoff = now - self._window
        with self._lock:
            dq = self._buckets.get(key)
            if dq is None:
                dq = self._buckets[key] = deque()
            while dq and dq[0] <= cutoff:
                dq.popleft()
            if len(dq) >= self._max:
                return False
            dq.append(now)
            # Prune idle buckets so the dict can't grow without bound.
            if len(self._buckets) > 10000:
                self._buckets = {k: v for k, v in self._buckets.items() if v}
            return True


def client_ip(request):
    """Best-effort client IP (Cloud Run sets X-Forwarded-For)."""
    fwd = request.headers.get("x-forwarded-for", "")
    if fwd:
        return fwd.split(",")[0].strip() or "unknown"
    client = request.client
    return client.host if client else "unknown"
