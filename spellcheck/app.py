"""BareLabel BERT spellcheck backend (Cloud Run).

POST /spellcheck  {text} -> {corrected, corrections[]}

Context-aware spelling correction for OCR'd ingredient text, using a
BERT masked-language model to rerank edit-distance candidates. This is
what the on-device dictionary corrector cannot do: pick the right word
from context when several candidates are plausible ("sulfite" ->
"sulfites" vs "sulfate").

v1 scope: corrects out-of-vocabulary words only. A word already in the
vocabulary is never touched (so valid-word confusions like pack/pork
are out of scope for now — section extraction mitigates those).

The model is baked into the Docker image at build time; it is loaded
lazily on first request (thread-safe) so container startup stays fast.
"""

import os
import re
import threading

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

app = FastAPI(title="barelabel-spellcheck")

MODEL_NAME = os.environ.get("SPELLCHECK_MODEL", "distilbert-base-uncased")
MIN_SCORE = float(os.environ.get("SPELLCHECK_MIN_SCORE", "0.05"))
MAX_EDIT_DISTANCE = 2
TOP_K = 20

WORD_RE = re.compile(r"[A-Za-z]+")


class SpellRequest(BaseModel):
    text: str


# --------------------------------------------------------------------------
# Vocabulary: flagged-ingredient words + common food words. A word in here
# is never "corrected". Loaded once at import.
# --------------------------------------------------------------------------

def _load_vocabulary() -> set:
    vocab = set()
    here = os.path.dirname(__file__)
    flagged_path = os.path.join(here, "flagged_ingredients.json")
    if os.path.exists(flagged_path):
        import json

        with open(flagged_path, encoding="utf-8") as f:
            data = json.load(f)
        for cat in data.values():
            for term in cat.get("ingredients", []):
                for w in re.findall(r"[a-z]+", term.lower()):
                    vocab.add(w)
    # Common food / label words, so valid words aren't sent to BERT.
    vocab.update(
        """
        flour sugar salt water milk butter ghee oil yogurt curd cheese cream
        rice wheat corn oat barley millet rye sorghum gram lentil bean pea
        peanut almond cashew walnut coconut garlic onion ginger chili pepper
        cumin coriander turmeric fennel mustard fenugreek asafoetida cardamom
        clove cinnamon honey jaggery yeast baking soda powder starch syrup
        honey vanilla cocoa chocolate fruit vegetable tomato potato onion
        powder fresh dried roasted fried natural product ingredients contains
        allergen advice nutrition facts manufactured marketed distributed
        imported storage keep cool dry place net weight best before expiry
        """.split()
    )
    return vocab


VOCAB = _load_vocabulary()

# --------------------------------------------------------------------------
# Model (lazy, thread-safe)
# --------------------------------------------------------------------------

_pipeline = None
_pipeline_lock = threading.Lock()


def get_pipeline():
    global _pipeline
    if _pipeline is None:
        with _pipeline_lock:
            if _pipeline is None:
                from transformers import pipeline

                _pipeline = pipeline(
                    "fill-mask", model=MODEL_NAME, top_k=TOP_K, device=-1
                )
    return _pipeline


# --------------------------------------------------------------------------
# Edit distance (bounded, for candidate generation)
# --------------------------------------------------------------------------

def _edit_distance(a: str, b: str, max_dist: int) -> int:
    n, m = len(a), len(b)
    if abs(n - m) > max_dist:
        return max_dist + 1
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        curr = [i] + [0] * m
        row_min = i
        for j in range(1, m + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            curr[j] = min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            row_min = min(row_min, curr[j])
        if row_min > max_dist:
            return max_dist + 1
        prev = curr
    return prev[m]


def _candidates(word: str) -> list:
    """Vocabulary words within edit distance 2 of the misspelled word."""
    out = []
    for v in VOCAB:
        if abs(len(v) - len(word)) > MAX_EDIT_DISTANCE:
            continue
        if _edit_distance(word, v, MAX_EDIT_DISTANCE) <= MAX_EDIT_DISTANCE:
            out.append(v)
    return out


# --------------------------------------------------------------------------
# Correction
# --------------------------------------------------------------------------

def correct_text(text: str) -> dict:
    """Returns {corrected, corrections[]} for the input text."""
    if not text or not text.strip():
        return {"corrected": text, "corrections": []}

    # word spans, so punctuation is preserved
    spans = [(m.group(), m.start(), m.end()) for m in WORD_RE.finditer(text)]
    words = [w for w, _, _ in spans]
    lower = [w.lower() for w in words]

    # indices of words worth correcting
    todo = [i for i, w in enumerate(lower) if w not in VOCAB]
    if not todo:
        return {"corrected": text, "corrections": []}

    pipe = get_pipeline()
    corrections = []
    # work on a mutable list of current words
    cur = list(words)

    for i in todo:
        original = cur[i]
        cands = set(_candidates(lower[i]))
        if not cands:
            continue
        masked_words = cur[:i] + ["[MASK]"] + cur[i + 1 :]
        masked_text = " ".join(masked_words)
        try:
            preds = pipe(masked_text)
        except Exception:
            continue
        best_word, best_score = None, 0.0
        for p in preds:
            token_str = p["token_str"].strip().lower()
            # single-word, alphabetic predictions only
            if not token_str or not token_str.isalpha():
                continue
            if token_str in cands and p["score"] > best_score:
                best_word, best_score = token_str, float(p["score"])
        if best_word and best_score >= MIN_SCORE and best_word != lower[i]:
            corrections.append(
                {
                    "original": original,
                    "corrected": best_word,
                    "confidence": round(best_score, 4),
                }
            )
            cur[i] = best_word
            lower[i] = best_word

    # rebuild, preserving original separators
    out = []
    pos = 0
    for idx, (w, s, e) in enumerate(spans):
        out.append(text[pos:s])
        out.append(cur[idx])
        pos = e
    out.append(text[pos:])
    return {"corrected": "".join(out), "corrections": corrections}


@app.get("/")
def root():
    return {"service": "barelabel-spellcheck", "model": MODEL_NAME}


@app.post("/spellcheck")
def spellcheck(req: SpellRequest):
    text = (req.text or "")[:2000]  # sanity cap
    try:
        result = correct_text(text)
    except Exception as e:  # never 500 on model hiccups; app falls back locally
        return JSONResponse(
            status_code=200,
            content={"corrected": text, "corrections": [], "error": str(e)[:200]},
        )
    return result
