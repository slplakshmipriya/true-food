"""Parity tests for the Python verdict-engine port.

These lock the port to the Android behavior (RuleBasedFoodClassifier,
FlaggedIngredientManager, CleanScoreCalculator, IngredientExplainer).
If an Android rule changes, the matching test here must change too.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from verdict import classifier, explainer, flagged, scoring, verdict_for


def test_classifier_brand_strip():
    assert classifier.classify("KRAFT, Cheddar Cheese") == "Cheese"


def test_classifier_secondary_segment():
    # Primary segment wins when it has a keyword ("milk" matches first).
    assert classifier.classify("Whole Milk, Greek Yogurt") == "Milk"
    # Secondary pass only fires when the primary segment has no keyword.
    assert classifier.classify("Greek Style, Yogurt Cups") == "Yogurt"


def test_classifier_parentheses_and_punct():
    assert classifier.classify("Bread (whole wheat), enriched") == "Bread"


def test_classifier_no_match():
    assert classifier.classify("Quinoa, amaranth") is None
    assert classifier.classify("") is None
    assert classifier.classify(None) is None


def test_scoring():
    assert scoring.score(True, 0, 0) == 100
    assert scoring.score(False, 0, 0) == scoring.UNAVAILABLE
    assert scoring.score(True, 3, 1) == 76  # 100 - 15 - 9
    assert scoring.score(True, 100, 100) == 5  # floored, never false zero


def test_explainer():
    assert "dyes" in explainer.reason_for("Artificial Colors")
    assert "avoid list" in explainer.reason_for("Dairy")
    assert "you chose" not in explainer.reason_for("Dairy").lower()
    assert explainer.reason_for("No Such Category") == \
        "This category is on the avoid list."
    assert explainer.reason_for(None) == \
        "This category is on the avoid list."


def test_flagged_exact_and_plural():
    cats = flagged.default_enabled_categories()
    assert "Dairy" in cats and "Artificial Colors" in cats
    m = flagged.analyze_ingredients("milk, red 40, water", cats)
    assert m["Dairy"] == ["milk"]
    assert m["Artificial Colors"] == ["red 40"]
    assert "water" not in str(m)


def test_flagged_plural_tolerance():
    # "carob bean gums" matches the listed term "carob bean gum".
    m = flagged.analyze_ingredients(
        "carob bean gums", {"Emulsifiers & Thickening Agents"})
    assert "carob bean gum" in m["Emulsifiers & Thickening Agents"]


def test_flagged_word_boundaries():
    # "milkweed" must not match the "milk" term.
    m = flagged.analyze_ingredients("milkweed extract", {"Dairy"})
    assert m == {}


def test_flagged_empty():
    assert flagged.analyze_ingredients("", None) == {}
    assert flagged.analyze_ingredients(None, None) == {}


def test_engine_clean_bread():
    # Clean under the default "everything on" avoid list.
    v = verdict_for(
        "Organic Bananas",
        "",
        "ORGANIC BANANAS",
    )
    assert v["clean"] is True
    assert v["score"] == 100
    assert v["flagged"] == []
    assert v["category"] is None  # "bananas" has no keyword mapping


def test_engine_default_avoid_list_flags_gluten():
    # Fresh-install behavior: every default_enabled category is on, so a
    # wheat bread flags Gluten. (The app's CLEAN screenshot for this bread
    # reflects the user's customized toggles, not the defaults.)
    v = verdict_for(
        "MULTIGRAIN ORGANIC BREAD, MULTIGRAIN",
        "SIMPLY BALANCED",
        "ORGANIC WHOLE WHEAT FLOUR, WATER, ORGANIC WHEAT FLOUR",
    )
    assert v["clean"] is False
    assert "Gluten" in [c["name"] for c in v["categories"]]


def test_engine_dirty_soda():
    v = verdict_for(
        "Coca-Cola Original Taste Soda",
        "Coca-Cola",
        "CARBONATED WATER, HIGH FRUCTOSE CORN SYRUP, CARAMEL COLOR, "
        "PHOSPHORIC ACID, NATURAL FLAVORS, CAFFEINE",
    )
    assert v["clean"] is False
    assert v["score"] < 100
    assert "high fructose corn syrup" in v["flagged"]
    assert any(c["name"] == "Processed Sweeteners & Fibers"
               for c in v["categories"])
    for c in v["categories"]:
        assert c["reason"]  # every flagged category explains itself


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items())
           if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"PASS {fn.__name__}")
    print(f"{len(fns)} tests passed")
