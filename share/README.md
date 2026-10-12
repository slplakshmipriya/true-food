# BareLabel web backend

Tiny Cloud Run service behind `https://barelabel.online`. Serves:

- `/` — marketing landing page (hero, how it works, flagged categories,
  Play Store CTA).
- `/lookup` — web product lookup: search a product name or barcode number,
  get the CLEAN/DIRTY verdict, Clean Score, and flagged ingredients with
  explanations. Powered by a Python port of the Android verdict engine
  (`verdict/`); verdicts match the app's default "everything on" avoid list.
  Needs `USDA_API_KEY` in the environment (see below). Successful lookups
  are cached in container-local memory (query → USDA record + image URL):
  30-day TTL, 100 MB budget, LRU eviction (`cache.py`). Misses and USDA
  errors are never cached.
- `/.well-known/assetlinks.json` — App Links verification so
  `barelabel.online/p/{gtin}` links open directly in the Android app.
- `/p/{gtin}` — Open Graph preview page. Chat apps (WhatsApp, Messages)
  unfurl the product name, verdict, and photo from its meta tags.
  Product facts arrive as query params from the app
  (`?v=clean&score=85&flagged=0&n=...&img=...&m=...`), so this service holds
  no API keys and stores nothing. `img` is the product photo URL (the app's
  cached Open Food Facts image); when absent the backend fetches it from
  Open Food Facts itself (free, keyless, cached 24h). `m` is a comma-separated
  list of retailer names carrying the item — rendered as "Available at"
  chips linking to each merchant's site search (exact per-offer deep links
  stay in the app, where the UPCitemdb lookup and affiliate logic live).
  The app omits `img=`/`n=` when Open Food Facts has the product — the
  backend fills both in — which keeps shared URLs short.

## Prerequisites

1. **The domain.** `barelabel.online` must be purchased and mapped to this
   Cloud Run service (step 4). Until then, the App Links intent-filter in
   the Android app will not verify and links fall back to the browser.
2. **Release keystore fingerprint.** `assetlinks.json` currently contains
   `__SHA256_FINGERPRINT__`. Replace it with the real SHA-256 of the
   release signing key:
   ```
   keytool -list -v -keystore <release-keystore.jks> -alias <alias> | grep SHA256
   ```
   (Debug builds use the debug keystore; App Links verification only
   matters for the release key that signs the Play Store build.)
3. **USDA API key (for `/lookup`).** The web lookup queries USDA FoodData
   Central server-side. Set it as an env var on the Cloud Run service —
   never in the repo:
   ```bash
   gcloud run services update barelabel-share --region us-east1 \
     --set-env-vars USDA_API_KEY=YOUR_KEY
   ```
   Without it, `/lookup` shows a "not configured" notice; everything else
   (landing page, share previews, assetlinks) works keyless.

## Deploy

```bash
cd share
gcloud run deploy barelabel-share \
  --source . \
  --region us-east1 \
  --allow-unauthenticated \
  --min-instances 0
```

Then map the domain (Google verifies ownership via a DNS TXT record it
shows you during this step):

```bash
gcloud run domain-mappings create \
  --service barelabel-share \
  --domain barelabel.online \
  --region us-east1
```

Verify:

```bash
curl https://barelabel.online/.well-known/assetlinks.json
curl "https://barelabel.online/p/0073947582301?v=clean&score=85&flagged=0&n=Test+Bread"
```

## Cost

Stays inside Cloud Run's free tier (2M requests/month) at any realistic
scale for this feature: effectively $0.

## Local run

```bash
pip install -r requirements.txt
uvicorn app:app --port 8080
```
