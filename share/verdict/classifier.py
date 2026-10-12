"""Port of RuleBasedFoodClassifier.java: keyword-based food categorization."""

import re

_PARENTHESES = re.compile(r"\(.*?\)")
_CLEAN_PUNCT = re.compile(r"[^a-zA-Z0-9\s,]")

_BRANDS = (
    "dannon", "yoplait", "kraft", "kellogg", "quaker", "nestle",
    "general mills",
)

_KEYWORD_CATEGORY = {
    "yogurt": "Yogurt",
    "cheese": "Cheese",
    "bread": "Bread",
    "cereal": "Cereal",
    "milk": "Milk",
    "cracker": "Crackers",
    "crackers": "Crackers",
    "chip": "Chips & Crisps",
    "chips": "Chips & Crisps",
    "pretzel": "Pretzels",
    "pretzels": "Pretzels",
    "pasta": "Pasta",
    "butter": "Butter & Spreads",
}


def classify(raw_description):
    """Category name, or None when no high-confidence rule matched."""
    if not raw_description or not raw_description.strip():
        return None

    cleaned = _PARENTHESES.sub("", raw_description)
    cleaned = _CLEAN_PUNCT.sub("", cleaned).lower().strip()

    parts = cleaned.split(",")
    primary = parts[0].strip()

    for brand in _BRANDS:
        if primary.startswith(brand + " "):
            primary = primary[len(brand):].strip()

    for token in primary.split():
        if token in _KEYWORD_CATEGORY:
            return _KEYWORD_CATEGORY[token]

    for part in parts[1:]:
        for token in part.strip().split():
            if token in _KEYWORD_CATEGORY:
                return _KEYWORD_CATEGORY[token]

    return None
