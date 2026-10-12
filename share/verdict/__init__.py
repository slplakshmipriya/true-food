"""Python port of BareLabel's verdict engine (Android app).

Mirrors, in order:
  RuleBasedFoodClassifier.java  -> classifier.py
  FlaggedIngredientManager.java -> flagged.py  (analyzeIngredients)
  CleanScoreCalculator.java     -> scoring.py
  IngredientExplainer.java      -> explainer.py

The web has no per-user avoid-list toggles, so every category uses its
``default_enabled`` value from flagged_ingredients.json. Keep this port in
lockstep with the Android sources: any rule change there must be mirrored
here, and vice versa.
"""

from .engine import verdict_for
from .ranking import (
    is_spam_brand,
    is_whole_word_match,
    normalize,
    ranked_candidates,
    select_top_pick,
)

__all__ = [
    "verdict_for",
    "normalize",
    "is_spam_brand",
    "is_whole_word_match",
    "ranked_candidates",
    "select_top_pick",
]
