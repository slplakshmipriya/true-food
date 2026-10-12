"""Python port of the Android app's USDA result ranking.

Mirrors, in order:
  StringNormalizer.java (normalize only) -> normalize
  UsdaSpamFilter.java                   -> NON_FOOD_BRAND_WORDS,
                                           is_spam_brand,
                                           is_whole_word_match,
                                           ranked_candidates
  UsdaApiClient.selectTopPick          -> select_top_pick

The Branded database contains records filed under obviously-fake,
non-food brand owners ("Multicom Publishing Incorporated",
"Bang Brothers Entertainment, Inc") with a bare generic description
("BREAD"). USDA ranks exact-description matches first, so a generic
query's top results are all spam. The ranking fixes that two ways:

  1. Brand-spam filter: records whose brand owner is a non-food company
     are demoted to the end of the results (callers skip them).
  2. Whole-word ranking: results whose description contains the query as
     a whole word ("ciabatta bread" for "bread") outrank substring
     matches ("breaded calamari").

Keep this port in lockstep with the Android sources: any rule change
there must be mirrored here, and vice versa.
"""

import re

_NON_ALNUM = re.compile(r"[^a-z0-9% -]")
_WHITESPACE = re.compile(r"\s+")

#: Brand-owner words that mark a record as definite non-food spam.
NON_FOOD_BRAND_WORDS = frozenset({
    "publishing",
    "software",
    "entertainment",
    "media",
    "films",
})


def normalize(s):
    """Null-safe normalization (lowercase, punctuation stripped).

    Mirrors StringNormalizer.normalize: keeps a-z, 0-9, %, space and
    hyphen; everything else becomes a space, then whitespace collapses.
    """
    s = _NON_ALNUM.sub(" ", (s or "").lower())
    return _WHITESPACE.sub(" ", s).strip()


def is_spam_brand(brand_owner):
    """True when the brand owner is an obvious non-food company (spam)."""
    padded = " " + normalize(brand_owner) + " "
    return any(" " + w + " " in padded for w in NON_FOOD_BRAND_WORDS)


def is_whole_word_match(description, query):
    """True when the description contains the query as a whole word."""
    nq = normalize(query)
    if not nq:
        return False
    return (" " + nq + " ") in (" " + normalize(description) + " ")


def ranked_candidates(foods, query):
    """Stable reorder of raw USDA results.

    Whole-word non-spam matches first, then other non-spam, then
    brand-spam last. Mirrors UsdaSpamFilter.rankedCandidates.
    """
    nq = normalize(query)
    whole, rest, spam = [], [], []
    for f in foods or []:
        if is_spam_brand(f.get("brandOwner") or ""):
            spam.append(f)
        elif nq and (" " + nq + " ") in (" " + normalize(f.get("description") or "") + " "):
            whole.append(f)
        else:
            rest.append(f)
    return whole + rest + spam


def select_top_pick(foods, ranked):
    """Top-pick selection (pure logic).

    First ranked non-spam hit with non-blank ingredients, else first
    ranked non-spam hit, else the raw first hit. None when no foods.
    Mirrors UsdaApiClient.selectTopPick.
    """
    if not foods:
        return None
    for f in ranked:
        if is_spam_brand(f.get("brandOwner") or ""):
            continue
        if not (f.get("ingredients") or "").strip():
            continue
        return f
    for f in ranked:
        if not is_spam_brand(f.get("brandOwner") or ""):
            return f
    return foods[0]
