"""Small web-app security helpers (no framework imports — unit-testable).

Covers the quick wins from the 2026-10-12 security review:
  - SECURITY_HEADERS: response headers applied by middleware in app.py
  - sanitize_image_url: http(s)-only allowlist for the img= query param
  - sanitize_gtin: digit-check before external data hits the OFF URL
  - MAX_QUERY_LEN: cap for reflected q/n/m inputs
"""

import re

GTIN_RE = re.compile(r"^[0-9]{8,14}$")

# Reflected user input is length-capped so a giant query can't be echoed
# back into a giant page (or force a 14s upstream round-trip per keystroke).
MAX_QUERY_LEN = 200

# Deliberately no Content-Security-Policy: the pages are zero-JS with
# inline styles and images from arbitrary https hosts, so a wrong CSP
# breaks rendering while adding little here.
SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
}


def sanitize_image_url(url: str) -> str:
    """Allow only http(s) image URLs from the img= query param.

    Blocks javascript:/data: schemes. Modern browsers won't execute
    javascript: in an <img> tag, but one browser quirk away from trouble
    is one too many.
    """
    url = (url or "").strip()
    if re.match(r"^https?://", url, re.IGNORECASE):
        return url
    return ""


def sanitize_gtin(gtin: str) -> str:
    """Return the GTIN only if it's all digits, else ''.

    USDA's gtinUpc is external data; never interpolate an unchecked value
    into the Open Food Facts URL.
    """
    gtin = (gtin or "").strip()
    return gtin if GTIN_RE.match(gtin) else ""
