"""Assembles a product verdict the way MainActivity does.

verdict_for(name, brand, ingredients) -> dict with:
  name, brand, category, clean (bool), score (0-100),
  flagged: [ingredient display terms, category order],
  categories: [{name, ingredients: [...], reason: ...}, ...]
"""

from . import classifier, explainer, flagged, scoring


def verdict_for(name, brand, ingredients):
    name = (name or "").strip()
    brand = (brand or "").strip()
    ingredients = (ingredients or "").strip()

    category_map = flagged.analyze_ingredients(ingredients)
    flat = [term for terms in category_map.values() for term in terms]
    clean = not flat
    score = scoring.score(True, len(flat), len(category_map))

    return {
        "name": name,
        "brand": brand,
        "category": classifier.classify(f"{name}, {brand}".strip(", ")),
        "clean": clean,
        "score": score,
        "flagged": flat,
        "categories": [
            {
                "name": category,
                "ingredients": terms,
                "reason": explainer.reason_for(category),
            }
            for category, terms in category_map.items()
        ],
    }
