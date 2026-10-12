"""Port of CleanScoreCalculator.java: pure 0-100 "Clean Score"."""

UNAVAILABLE = -1
_PER_CATEGORY_PENALTY = 15
_PER_INGREDIENT_PENALTY = 3
_MIN_SCORE = 5


def score(available, flagged_ingredients, flagged_categories):
    """available=False -> UNAVAILABLE; no flags -> 100; else penalized."""
    if not available:
        return UNAVAILABLE
    ingredients = max(0, flagged_ingredients)
    categories = max(0, flagged_categories)
    if ingredients <= 0 and categories <= 0:
        return 100
    penalty = (categories * _PER_CATEGORY_PENALTY
               + ingredients * _PER_INGREDIENT_PENALTY)
    return max(_MIN_SCORE, 100 - penalty)
