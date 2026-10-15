#!/bin/bash
#
# Deploy the BareLabel share backend to Cloud Run and clean up
# old revisions/images so Artifact Registry storage doesn't accumulate.
#
# Usage: ./deploy-share.sh [branch]
#   branch defaults to web-landing.
#
# Prereqs: Cloud Shell with gcloud authenticated, ~/true-food checkout.

set -euo pipefail

BRANCH="${1:-web-landing}"
PROJECT="garednx"
REGION="us-east1"
SERVICE="barelabel-share"
REPO="cloud-run-source-deploy"
PKG="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}/${SERVICE}"

echo "=== Checking out branch: ${BRANCH} ==="
cd ~/true-food
git fetch origin
git checkout "${BRANCH}"
git reset --hard "origin/${BRANCH}"

echo "=== Deploying ${SERVICE} from $(git rev-parse --short HEAD) ==="
cd share
gcloud run deploy "${SERVICE}" \
  --source . \
  --region "${REGION}" \
  --project "${PROJECT}" \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 10

echo "=== Cleaning up old revisions ==="
LIVE_REV=$(gcloud run services describe "${SERVICE}" \
  --region "${REGION}" --project "${PROJECT}" \
  --format='value(status.latestReadyRevisionName)')
echo "Live revision: ${LIVE_REV}"
for rev in $(gcloud run revisions list --service "${SERVICE}" \
    --region "${REGION}" --project "${PROJECT}" \
    --format='value(metadata.name)'); do
  if [ "${rev}" != "${LIVE_REV}" ]; then
    echo "Deleting revision: ${rev}"
    gcloud run revisions delete "${rev}" \
      --region "${REGION}" --project "${PROJECT}" --quiet \
      || echo "  (delete failed, continuing)"
  fi
done

echo "=== Cleaning up old images ==="
LIVE_IMG=$(gcloud run services describe "${SERVICE}" \
  --region "${REGION}" --project "${PROJECT}" \
  --format='value(spec.template.spec.containers[0].image)')
echo "Live image: ${LIVE_IMG}"
for ver in $(gcloud artifacts docker images list "${PKG}" \
    --project "${PROJECT}" --format='value(version)'); do
  full="${PKG}@${ver}"
  if [ "${full}" != "${LIVE_IMG}" ]; then
    echo "Deleting image: ${full}"
    gcloud artifacts docker images delete "${full}" \
      --project "${PROJECT}" --quiet \
      || echo "  (delete failed -- likely IAM; continuing)"
  fi
done

echo "=== Done ==="
gcloud run services describe "${SERVICE}" \
  --region "${REGION}" --project "${PROJECT}" \
  --format='value(status.url)'
