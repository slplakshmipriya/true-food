"""Tests for SEO foundations: robots.txt, sitemap.xml, meta/OG tags."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from starlette.requests import Request

import app as app_module
from pages import landing_page, lookup_page


def _req(path="/"):
    return Request({
        "type": "http", "method": "GET", "path": path,
        "headers": [(b"user-agent", b"test-agent")],
    })


def test_robots_txt():
    r = app_module.robots_txt()
    assert r.status_code == 200
    body = r.body.decode()
    assert "User-agent: *" in body
    assert "Sitemap: https://barelabel.online/sitemap.xml" in body


def test_sitemap_xml_is_valid():
    r = app_module.sitemap_xml()
    assert r.status_code == 200
    root = ET.fromstring(r.body.decode())
    locs = [e.text for e in root.iter()
            if e.tag.endswith("loc")]
    assert "https://barelabel.online/" in locs
    assert "https://barelabel.online/lookup" in locs


def test_landing_has_social_and_canonical_tags():
    html = landing_page("desktop")
    assert "rel='canonical' href='https://barelabel.online/'" in html
    assert "property='og:title'" in html
    assert "property='og:description'" in html
    assert "property='og:type' content='website'" in html
    assert "property='og:url' content='https://barelabel.online/'" in html
    assert "property='og:image'" in html
    assert "static/app-screenshot.png" in html
    assert "name='twitter:card' content='summary_large_image'" in html


def test_lookup_has_social_and_canonical_tags():
    html = lookup_page("bread", None, None, "desktop")
    assert "rel='canonical' href='https://barelabel.online/lookup'" in html
    assert "property='og:title'" in html
    assert "name='twitter:card'" in html
    # Query is escaped in the title/OG tags.
    html2 = lookup_page("<script>", None, None, "desktop")
    assert "<script>" not in html2


def test_share_page_has_twitter_card_and_canonical():
    r = app_module.product_page(
        _req("/p/038000265013"), gtin="038000265013",
        v="", score=-1, flagged=0,
        n="Test Product", img="https://example.com/x.png", m="")
    body = r.body.decode()
    assert 'name="twitter:card" content="summary_large_image"' in body
    assert 'rel="canonical"' in body
