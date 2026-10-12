"""Port of FlaggedIngredientManager.java's pure matching pass.

Loads flagged_ingredients.json once, compiles every term's regex once
(word-boundary, irregular-whitespace tolerant, plural "s" on the final
word — same as the Java phrasePattern), then matches without I/O.
"""

import json
import re
from pathlib import Path

_DATA = Path(__file__).with_name("data") / "flagged_ingredients.json"

_TERMS = None          # list of (category, display_term, compiled_pattern)
_DEFAULTS = None       # category -> default_enabled


def _phrase_pattern(term):
    words = term.strip().split()
    parts = [re.escape(w) for w in words]
    parts[-1] = parts[-1] + "s?"
    return r"\b" + r"\s+".join(parts) + r"\b"


def _load():
    global _TERMS, _DEFAULTS
    if _TERMS is not None:
        return
    terms, defaults = [], {}
    try:
        root = json.loads(_DATA.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        root = {}
    for category, obj in root.items():
        if not isinstance(obj, dict):
            continue
        defaults[category] = bool(obj.get("default_enabled", True))
        for raw in obj.get("ingredients") or []:
            term = (raw or "").strip()
            if not term:
                continue
            terms.append((
                category,
                term,
                re.compile(_phrase_pattern(term.lower())),
            ))
    _TERMS, _DEFAULTS = terms, defaults


def default_enabled_categories():
    """Categories enabled when the user has no sticky toggles (web default)."""
    _load()
    return {c for c, on in _DEFAULTS.items() if on}


def analyze_ingredients(raw_ingredients_text, enabled_categories=None):
    """category -> [matched display terms], insertion-ordered. Pure."""
    _load()
    result = {}
    if not raw_ingredients_text or not raw_ingredients_text.strip():
        return result
    if enabled_categories is None:
        enabled_categories = default_enabled_categories()
    if not _TERMS or not enabled_categories:
        return result
    lowered = raw_ingredients_text.lower()
    for category, display_term, pattern in _TERMS:
        if category not in enabled_categories:
            continue
        if pattern.search(lowered):
            matched = result.setdefault(category, [])
            if display_term not in matched:
                matched.append(display_term)
    return result
