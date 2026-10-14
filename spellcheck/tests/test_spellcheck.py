"""Tests for the spellcheck service. The BERT pipeline is mocked so tests
run without downloading the model."""

import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient

import app as spell_app


def _fake_pipe_factory(mapping):
    """mapping: masked-sentence substring -> list of {token_str, score}."""

    def fake_pipe(masked_text, **kwargs):
        for key, preds in mapping.items():
            if key in masked_text:
                return preds
        return []

    return fake_pipe


def test_empty_text():
    assert spell_app.correct_text("")["corrected"] == ""
    assert spell_app.correct_text("   ")["corrections"] == []


def test_valid_words_untouched():
    with patch.object(spell_app, "get_pipeline", return_value=lambda *a, **k: []):
        r = spell_app.correct_text("rice flour, salt, water")
        assert r["corrected"] == "rice flour, salt, water"
        assert r["corrections"] == []


def test_bert_rerank_picks_candidate():
    # "suger" has a unique best ("sugar" d=1): decisive, no BERT needed.
    # The pipeline must not even be consulted.
    called = []

    def spy_pipe(*a, **k):
        called.append(True)
        return []

    with patch.object(spell_app, "get_pipeline", return_value=spy_pipe):
        r = spell_app.correct_text("contains suger")
        assert r["corrected"] == "contains sugar"
        assert not called, "BERT should not run for a unique best"
        assert len(r["corrections"]) == 1
        assert r["corrections"][0]["original"] == "suger"
        assert r["corrections"][0]["corrected"] == "sugar"


def test_bert_tie_broken_by_context():
    # Constructed tie: "alph" is d=1 from both "alpha" and "alphi".
    # Edit distance cannot decide; BERT's context score picks.
    spell_app.VOCAB.update(["alpha", "alphi"])
    try:
        fake = _fake_pipe_factory(
            {
                "[MASK]": [
                    {"token_str": "alpha", "score": 0.55},
                    {"token_str": "alphi", "score": 0.12},
                ]
            }
        )
        with patch.object(spell_app, "get_pipeline", return_value=fake):
            r = spell_app.correct_text("contains alph")
            assert r["corrected"] == "contains alpha"
    finally:
        spell_app.VOCAB.discard("alpha")
        spell_app.VOCAB.discard("alphi")


def test_low_score_keeps_original():
    fake = _fake_pipe_factory(
        {"[MASK]": [{"token_str": "sulfites", "score": 0.01}]}
    )
    with patch.object(spell_app, "get_pipeline", return_value=fake):
        r = spell_app.correct_text("contains sulfite")
        assert r["corrected"] == "contains sulfite"
        assert r["corrections"] == []


def test_no_candidates_keeps_original():
    # "badusha" has no vocab word within edit distance 2
    with patch.object(
        spell_app, "get_pipeline", return_value=_fake_pipe_factory({})
    ):
        r = spell_app.correct_text("mithai badusha")
        assert r["corrected"] == "mithai badusha"


def test_punctuation_preserved():
    fake = _fake_pipe_factory(
        {"[MASK]": [{"token_str": "sugar", "score": 0.9}]}
    )
    with patch.object(spell_app, "get_pipeline", return_value=fake):
        # "sugr" is OOV; "sugar" must be in vocab for this test
        if "sugar" not in spell_app.VOCAB:
            spell_app.VOCAB.add("sugar")
        r = spell_app.correct_text("sugr, salt!")
        assert r["corrected"] == "sugar, salt!"


def test_short_words_skipped():
    # "AM" must never become "ham": short fragments belong to phrases,
    # which the on-device phrase corrector handles with more context.
    with patch.object(spell_app, "get_pipeline", return_value=lambda *a, **k: []):
        r = spell_app.correct_text("AM purpose Fou")
        assert r["corrected"] == "AM purpose Fou"
        assert r["corrections"] == []


def test_endpoint_shape():
    fake = _fake_pipe_factory({"[MASK]": [{"token_str": "salt", "score": 0.9}]})
    with patch.object(spell_app, "get_pipeline", return_value=fake):
        client = TestClient(spell_app.app)
        resp = client.post("/spellcheck", json={"text": "rice, salt"})
        assert resp.status_code == 200
        body = resp.json()
        assert "corrected" in body and "corrections" in body


def test_endpoint_never_500s():
    def boom(*a, **k):
        raise RuntimeError("model exploded")

    with patch.object(spell_app, "get_pipeline", return_value=boom):
        client = TestClient(spell_app.app)
        resp = client.post("/spellcheck", json={"text": "sulfite"})
        assert resp.status_code == 200
        assert resp.json()["corrected"] == "sulfite"
