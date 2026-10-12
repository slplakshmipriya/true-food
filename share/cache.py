"""Container-local LRU cache with TTL and a byte budget.

The /lookup route caches (normalized query) -> (USDA food record, OFF
image URL) here. Pure in-memory: each Cloud Run instance holds its own
cache, so a cold start (min-instances 0) begins empty and instances
don't share entries. Thread-safe for uvicorn's threadpool (sync
endpoints run in worker threads).

Entries expire 30 days after being stored; the whole cache is capped
at 100 MB and evicts least-recently-used entries first. Only
successful lookups are cached — misses and USDA errors are not, so a
product added to USDA later still becomes visible.
"""

import json
import threading
import time
from collections import OrderedDict

TTL_SECONDS = 30 * 24 * 3600   # 30 days
MAX_BYTES = 100 * 1024 * 1024  # 100 MB


class LruCache:
    """Fixed-byte-budget LRU mapping of str -> JSON-serializable value."""

    def __init__(self, ttl_seconds=TTL_SECONDS, max_bytes=MAX_BYTES):
        self._ttl = ttl_seconds
        self._max = max_bytes
        self._lock = threading.Lock()
        # key -> (payload_bytes, expires_at, size); OrderedDict = LRU order.
        self._entries = OrderedDict()
        self._total = 0

    def get(self, key):
        """Return the cached value, or None on miss or expiry."""
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            payload, expires_at, size = entry
            if expires_at <= time.time():
                del self._entries[key]
                self._total -= size
                return None
            self._entries.move_to_end(key)
            return json.loads(payload)

    def put(self, key, value):
        """Store a JSON-serializable value, evicting LRU entries over budget."""
        payload = json.dumps(value).encode("utf-8")
        size = len(payload)
        if size > self._max:
            return  # a single entry bigger than the budget: don't cache it
        with self._lock:
            old = self._entries.pop(key, None)
            if old is not None:
                self._total -= old[2]
            self._entries[key] = (payload, time.time() + self._ttl, size)
            self._total += size
            while self._total > self._max:
                _, (_, _, evicted) = self._entries.popitem(last=False)
                self._total -= evicted

    def __len__(self):
        with self._lock:
            return len(self._entries)

    def total_bytes(self):
        """Current bytes held (introspection for tests/monitoring)."""
        with self._lock:
            return self._total


def cache_key(query):
    """Normalize a lookup query into a cache key.

    Case- and whitespace-insensitive; barcode queries ignore leading
    zeros, mirroring pick_product's GTIN matching.
    """
    q = (query or "").strip().lower()
    if q.isdigit():
        q = q.lstrip("0") or "0"
    return q
