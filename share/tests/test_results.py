"""Tests for the results-list lookup flow: /lookup list page + /v detail."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from starlette.requests import Request

import app as app_module
from verdict import verdict_for


def _req(ip="10.9.9.71"):
    return Request({
        "type": "http", "method": "GET", "path": "/lookup",
        "headers": [(b"user-agent", b"test-agent"),
                    (b"x-forwarded-for", ip.encode())],
    })


def _food(fdc, desc, brand, ingredients, gtin=""):
    return {"fdcId": fdc, "description": desc, "brandName": brand,
            "brandOwner": brand, "ingredients": ingredients, "gtinUpc": gtin}


def _patch_foods(foods):
    """Stub the search layer: no USDA, no OFF network."""
    orig_key = app_module.USDA_API_KEY
    orig_get = app_module.get_foods
    orig_img = app_module.food_image
    app_module.USDA_API_KEY = "test-key"
    visible = [f for f in foods]  # already ranked, no spam
    app_module.get_foods = lambda q: (foods, visible)
    app_module.food_image = lambda f: None
    return orig_key, orig_get, orig_img


def _restore(orig):
    ok, og, oi = orig
    app_module.USDA_API_KEY = ok
    app_module.get_foods = og
    app_module.food_image = oi


def test_generic_query_shows_results_list_with_cleanest_pick():
    foods = [
        _food(1, "Whole Wheat Bread", "Bakery Co", "whole wheat flour, sugar, yeast"),
        _food(2, "White Bread", "Bakery Co", "wheat flour, water, yeast"),
        _food(3, "Banana Bread", "Bakery Co", "banana, flour, sugar, butter, eggs"),
    ]
    orig = _patch_foods(foods)
    try:
        body = app_module.lookup(_req("10.9.9.72"), q="bread").body.decode()
    finally:
        _restore(orig)
    assert "Cleanest match" in body
    assert "matches for" in body
    assert "/v?fdc=1&amp;q=bread" in body or "/v?fdc=1&q=bread" in body
    # Highest-scoring bread is featured as the cleanest pick.
    scored = [(verdict_for(f["description"], f["brandName"],
                           f["ingredients"])["score"], f["description"])
              for f in foods]
    best = max(scored)[1]
    assert best in body.split("Cleanest match")[1].split("matches for")[0]


def test_no_ingredient_data_excluded_from_cleanest_pick():
    foods = [
        _food(1, "Mystery Bread", "Bakery Co", ""),  # would score a false 100
        _food(2, "Water Bread", "Bakery Co", "water, flour"),
    ]
    orig = _patch_foods(foods)
    try:
        body = app_module.lookup(_req("10.9.9.73"), q="bread").body.decode()
    finally:
        _restore(orig)
    assert "No ingredient data" in body
    pick_section = body.split("Cleanest match")[1].split("matches for")[0]
    assert "Water Bread" in pick_section
    assert "Mystery Bread" not in pick_section


def test_exact_product_match_goes_straight_to_verdict():
    foods = [_food(9, "Cheez-It", "Kellogg's", "wheat flour, cheese", "021000658013")]
    orig = _patch_foods(foods)
    try:
        body = app_module.lookup(_req("10.9.9.74"), q="cheez-it").body.decode()
    finally:
        _restore(orig)
    assert "Clean Score" in body
    assert "Cleanest match" not in body


def test_barcode_exact_match_goes_straight_to_verdict():
    foods = [_food(9, "Cheez-It", "Kellogg's", "wheat flour, cheese", "021000658013")]
    orig = _patch_foods(foods)
    try:
        body = app_module.lookup(_req("10.9.9.75"),
                                 q="021000658013").body.decode()
    finally:
        _restore(orig)
    assert "Clean Score" in body
    assert "Cleanest match" not in body


def test_detail_route_renders_full_verdict():
    foods = [_food(5, "Rye Bread", "Bakery Co", "rye flour, water, salt")]
    orig = _patch_foods(foods)
    try:
        body = app_module.verdict_detail(
            _req("10.9.9.76"), fdc=5, q="bread").body.decode()
    finally:
        _restore(orig)
    assert "Clean Score" in body
    assert "Rye Bread" in body


def test_detail_route_unknown_fdc_shows_error():
    foods = [_food(5, "Rye Bread", "Bakery Co", "rye flour, water, salt")]
    orig = _patch_foods(foods)
    try:
        body = app_module.verdict_detail(
            _req("10.9.9.77"), fdc=999, q="bread").body.decode()
    finally:
        _restore(orig)
    assert "isn" in body and "search again" in body


def test_search_foods_filters_brand_spam():
    orig_key = app_module.USDA_API_KEY
    orig_search = app_module.usda_search
    app_module.USDA_API_KEY = "test-key"
    app_module.usda_search = lambda q: [
        _food(1, "BREAD", "Multicom Publishing Incorporated", "wheat"),
        _food(2, "Whole Wheat Bread", "Bakery Co", "wheat, water"),
    ]
    try:
        foods, visible = app_module.search_foods("bread")
    finally:
        app_module.USDA_API_KEY = orig_key
        app_module.usda_search = orig_search
    assert len(foods) == 2
    assert [f["fdcId"] for f in visible] == [2]


def test_is_exact_product_match():
    m = app_module.is_exact_product_match
    assert m({"description": "Cheez-It"}, "cheez-it")
    assert not m({"description": "Whole Wheat Bread"}, "bread")
    assert not m(None, "bread")
    assert not m({"description": "Bread"}, "")
