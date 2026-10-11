# BareLabel share backend

Tiny Cloud Run service behind `https://barelabel.app`. Serves:

- `/.well-known/assetlinks.json` — App Links verification so
  `barelabel.app/p/{gtin}` links open directly in the Android app.
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

## Prerequisites

1. **The domain.** `barelabel.app` must be purchased and mapped to this
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
  --domain barelabel.app \
  --region us-east1
```

Verify:

```bash
curl https://barelabel.app/.well-known/assetlinks.json
curl "https://barelabel.app/p/0073947582301?v=clean&score=85&flagged=0&n=Test+Bread"
```

## Cost

Stays inside Cloud Run's free tier (2M requests/month) at any realistic
scale for this feature: effectively $0.

## Local run

```bash
pip install -r requirements.txt
uvicorn app:app --port 8080
```
