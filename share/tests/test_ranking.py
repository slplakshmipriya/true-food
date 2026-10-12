"""Parity tests for the Python ranking port.

These lock the port to the Android behavior (StringNormalizer.normalize,
UsdaSpamFilter, UsdaApiClient.selectTopPick). If an Android rule changes,
the matching test here must change too.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from verdict import (
    is_spam_brand,
    is_whole_word_match,
    normalize,
    ranked_candidates,
    select_top_pick,
)


def food(description, brand_owner="", ingredients="x"):
    return {
        "description": description,
        "brandOwner": brand_owner,
        "ingredients": ingredients,
    }


def test_normalize_parity():
    # Lowercase, punctuation -> space, hyphen kept, whitespace collapsed.
    assert normalize("Kellogg's Cheez-It Crackers!") == "kellogg s cheez-it crackers"
    assert normalize("  BREAD\t(Bakery) ") == "bread bakery"
    assert normalize(None) == ""
    assert normalize("") == ""


def test_spam_brand_words():
    assert is_spam_brand("Multicom Publishing Incorporated")
    assert is_spam_brand("Bang Brothers Entertainment, Inc")
    assert is_spam_brand("Some Media Group LLC")
    assert not is_spam_brand("Kellogg")
    assert not is_spam_brand("Kellogg Company")
    assert not is_spam_brand("")
    assert not is_spam_brand(None)
    # "media" as a substring of a longer word is not spam ("immediate").
    assert not is_spam_brand("Immediate Foods Co")


def test_whole_word_match():
    assert is_whole_word_match("Ciabatta Bread, Bakery Fresh", "bread")
    assert not is_whole_word_match("Breaded Calamari Rings", "bread")
    assert is_whole_word_match("Kellogg's Cheez It Crackers Original", "cheez it crackers")
    assert not is_whole_word_match("Anything", "")
    assert not is_whole_word_match("Anything", None)


def test_ranked_candidates_order():
    spam = food("BREAD", "Multicom Publishing Incorporated")
    substring = food("Breaded Calamari Rings", "Seafood Co")
    whole = food("Ciabatta Bread", "Bakery Fresh")
    ranked = ranked_candidates([spam, substring, whole], "bread")
    assert ranked == [whole, substring, spam]


def test_ranked_candidates_stable_within_buckets():
    a = food("Whole Wheat Bread", "Brand A")
    b = food("White Bread", "Brand B")
    ranked = ranked_candidates([a, b], "bread")
    assert ranked == [a, b]


def test_select_top_pick_skips_spam_and_blank_ingredients():
    spam = food("BREAD", "Multicom Publishing Incorporated", "flour")
    blank = food("Mystery Bread", "Brand X", "   ")
    good = food("Whole Wheat Bread", "Brand X", "whole wheat flour")
    ranked = ranked_candidates([spam, blank, good], "bread")
    assert select_top_pick([spam, blank, good], ranked) is good


def test_select_top_pick_falls_back_to_nonspam_without_ingredients():
    blank = food("Mystery Bread", "Brand X", "")
    ranked = ranked_candidates([blank], "bread")
    assert select_top_pick([blank], ranked) is blank


def test_select_top_pick_empty():
    assert select_top_pick([], []) is None


def test_cheez_it_scenario():
    # USDA's raw order for "cheez it crackers" puts a vegan knockoff first;
    # whole-word ranking must surface the real Kellogg's Cheez-It.
    knockoff = food(
        "CHEDDAR CHEEZE ORGANIC VEGAN CRACKERS, CHEDDAR CHEEZE",
        "PATAGONIA PROVISIONS",
        "cashews, ...",
    )
    real = food(
        "Kellogg's Cheez It Crackers Original 1.5oz",
        "Kellogg's Cheez It",
        "enriched flour, ...",
    )
    foods = [knockoff, real]
    ranked = ranked_candidates(foods, "cheez it crackers")
    assert ranked[0] is real
    assert select_top_pick(foods, ranked) is real
