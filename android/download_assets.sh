#!/usr/bin/env bash
# Downloads the pretrained MediaPipe Face Landmarker (Model C) into app assets.
# Model A (model_a_v1.tflite + model_a_labels.txt) is already committed in the same folder.
set -euo pipefail
cd "$(dirname "$0")/app/src/main/assets"
curl -fsSL -o face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
echo "✓ face_landmarker.task"; ls -la
