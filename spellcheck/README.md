# BareLabel spellcheck backend

BERT-based spelling correction for OCR'd ingredient text. The Android app
POSTs extracted ingredient text here (premium feature); the service
returns the corrected text. The app falls back to its on-device
corrector when offline or when this service errors.

## Endpoint

`POST /spellcheck`

```json
{ "text": "AM purpose Fou, yogurt, Ghee" }
```

```json
{
  "corrected": "all purpose flour, yogurt, ghee",
  "corrections": [
    {"original": "Fou", "corrected": "flour", "confidence": 0.87}
  ]
}
```

Never 500s: model hiccups return the input unchanged with an `error`
field, and the app falls back locally.

## How it works

For each word not in the vocabulary (flagged-ingredient words from
`flagged_ingredients.json` + common food words), edit-distance
candidates (≤ 2) are generated and reranked by a BERT masked-LM
(DistilBERT by default). A candidate replaces the word only if BERT's
score ≥ `SPELLCHECK_MIN_SCORE` (default 0.05). Words already in the
vocabulary are never touched.

Keep `flagged_ingredients.json` in sync with
`app/src/main/assets/flagged_ingredients.json` when the list changes.

## Deploy (Cloud Run)

```bash
cd ~/true-food/spellcheck
gcloud run deploy barelabel-spellcheck \
  --source . \
  --region us-east1 \
  --project garednx \
  --allow-unauthenticated \
  --memory 4Gi \
  --cpu 2 \
  --min-instances 0 \
  --max-instances 5 \
  --timeout 120
```

Notes:

- The Docker build downloads torch (CPU) and bakes the model into the
  image; the first build takes a while (~1.5 GB image).
- `--memory 4Gi` fits the model + torch comfortably.
- Override the model with `--set-env-vars SPELLCHECK_MODEL=...`.
- After deploy, set the service URL in the app's `ServerSpellChecker`.
