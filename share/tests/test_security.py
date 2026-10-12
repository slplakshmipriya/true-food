"""Tests for the web-app security quick wins (2026-10-12 review)."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from security import (
    MAX_QUERY_LEN,
    SECURITY_HEADERS,
    sanitize_gtin,
    sanitize_image_url,
)


def test_security_headers_present():
    assert SECURITY_HEADERS["X-Content-Type-Options"] == "nosniff"
    assert SECURITY_HEADERS["X-Frame-Options"] == "DENY"
    assert SECURITY_HEADERS["Referrer-Policy"] == "no-referrer"


def test_image_url_allows_http_and_https():
    assert sanitize_image_url("https://example.com/x.png") == \
        "https://example.com/x.png"
    assert sanitize_image_url("http://example.com/x.png") == \
        "http://example.com/x.png"
    # Scheme match is case-insensitive, like browsers treat it.
    assert sanitize_image_url("HTTPS://example.com/x.png") == \
        "HTTPS://example.com/x.png"


def test_image_url_blocks_dangerous_schemes():
    assert sanitize_image_url("javascript:alert(2)") == ""
    assert sanitize_image_url("JaVaScRiPt:alert(2)") == ""
    assert sanitize_image_url("data:image/svg+xml,<svg/>") == ""
    assert sanitize_image_url("") == ""
    assert sanitize_image_url("   ") == ""
    # Protocol-relative and bare paths are not allowlisted either.
    assert sanitize_image_url("//example.com/x.png") == ""
    assert sanitize_image_url("/x.png") == ""


def test_gtin_allows_digits_only():
    assert sanitize_gtin("038000265013") == "038000265013"
    assert sanitize_gtin("  038000265013  ") == "038000265013"
    assert sanitize_gtin("00038000175497") == "00038000175497"


def test_gtin_rejects_non_digits():
    assert sanitize_gtin("../../etc/passwd") == ""
    assert sanitize_gtin("038000265013;rm") == ""
    assert sanitize_gtin("") == ""
    assert sanitize_gtin("1234567") == ""  # too short for GTIN_RE
    assert sanitize_gtin("123456789012345") == ""  # too long


def test_max_query_len_is_sane():
    assert MAX_QUERY_LEN == 200
