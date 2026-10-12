"""Zero-JS, server-side analytics counters.

Container-local like the cache: counts live in process memory, so they
reset on cold start, new revisions, and deploys, and each Cloud Run
instance keeps its own. Good for directional numbers (which CTA
placement converts, USDA failure rate), not for durable reporting.

Keys (flat, colon-separated):
  landing:<platform>          landing page views, by platform
  search:text | search:barcode lookups submitted (non-empty q)
  usda:ok | usda:fail         USDA round-trips (fail = final, after retry)
  click:<platform>:<src>      taps on a "get the app" CTA, via /go/play
"""
import threading
from collections import Counter


class Stats:
    def __init__(self):
        self._lock = threading.Lock()
        self._c = Counter()

    def incr(self, key, n=1):
        with self._lock:
            self._c[key] += n

    def snapshot(self):
        with self._lock:
            return dict(self._c)

    def reset(self):
        with self._lock:
            self._c.clear()


stats = Stats()
