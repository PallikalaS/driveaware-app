#!/usr/bin/env bash
# Deploy the backend to Cloud Run (source deploy → Cloud Build → Artifact Registry).
# Usage: GCP_PROJECT=driveaware-cs663-swathi [GEMINI_MODEL=<tuned-endpoint>] bash deploy.sh
# Cloud Run runs in a single region (us-central1); Gemini is called in the
# Vertex AI "us" multi-region, where Gemini 3.5 Flash tuning/serving lives.
set -euo pipefail
: "${GCP_PROJECT:?set GCP_PROJECT}"
REGION="${GCP_REGION:-us-central1}"
SA="driveaware-backend@${GCP_PROJECT}.iam.gserviceaccount.com"
gcloud run deploy driveaware-backend \
  --project "$GCP_PROJECT" --region "$REGION" --source . \
  --service-account "$SA" \
  --allow-unauthenticated \
  --min-instances 0 --max-instances 3 --memory 512Mi --timeout 30 \
  --set-env-vars "GCP_PROJECT=${GCP_PROJECT},VERTEX_LOCATION=${VERTEX_LOCATION:-us},GEMINI_MODEL=${GEMINI_MODEL:-gemini-3.5-flash},APP_TOKEN=${APP_TOKEN:-}"
gcloud run services describe driveaware-backend --project "$GCP_PROJECT" --region "$REGION" --format='value(status.url)'
