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
    # "suger" is OOV; "sugar" is within edit distance 1 and BERT
    # confirms it by context score.
    fake = _fake_pipe_factory(
        {
            "[MASK]": [
                {"token_str": "sugar", "score": 0.62},
                {"token_str": "salt", "score": 0.11},
            ]
        }
    )
    with patch.object(spell_app, "get_pipeline", return_value=fake):
        r = spell_app.correct_text("contains suger")
        assert r["corrected"] == "contains sugar"
        assert len(r["corrections"]) == 1
        assert r["corrections"][0]["original"] == "suger"
        assert r["corrections"][0]["corrected"] == "sugar"


def test_bert_tie_broken_by_context():
    # "flou" is OOV with two candidates ("flour" d=1, "food" d=2).
    # The local on-device corrector would punt on ambiguity; BERT's
    # context score picks the winner.
    fake = _fake_pipe_factory(
        {
            "[MASK]": [
                {"token_str": "flour", "score": 0.55},
                {"token_str": "food", "score": 0.12},
            ]
        }
    )
    with patch.object(spell_app, "get_pipeline", return_value=fake):
        r = spell_app.correct_text("rice flou")
        assert r["corrected"] == "rice flour"


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
