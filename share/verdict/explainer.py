"""Port of IngredientExplainer.java: plain-English text per flagged category."""

_FALLBACK = "This category is on the avoid list."

_REASONS = {
    "Dairy":
        "Milk-derived ingredients. Dairy is on the avoid list.",
    "Gluten":
        "Wheat or other gluten sources. Often avoided for celiac "
        "disease or gluten sensitivity.",
    "Non-Vegetarian":
        "Meat, gelatin, or other animal-derived ingredients.",
    "Non-Vegan":
        "Animal-derived ingredients such as milk, egg, or honey.",
    "Artificial Colors":
        "Synthetic dyes added only for appearance; they carry no nutrition.",
    "Preservatives":
        "Chemical preservatives that extend shelf life. A common avoid "
        "for people eating minimally processed food.",
    "Artificial Sweeteners":
        "Zero-calorie sweeteners. Heavily processed and a frequent "
        "clean-eating avoid.",
    "Emulsifiers & Thickening Agents":
        "Texture additives such as gums and lecithins. Often a sign of "
        "heavy processing.",
    "Flavor Enhancers & Additives":
        "Added flavor systems (for example MSG or yeast extract). A "
        "broad marker of ultra-processed food.",
    "Fats & Hydrogenated Oils":
        "Hydrogenated or interesterified oils. A marker of industrial "
        "processing and added trans-fat risk.",
    "Processed Sweeteners & Fibers":
        "Refined sweeteners and isolated fibers added back to foods. "
        "Usually heavily processed.",
}


def reason_for(category):
    """Reason text for a flagged category; never empty."""
    if category is None:
        return _FALLBACK
    return _REASONS.get(category.strip(), _FALLBACK)
