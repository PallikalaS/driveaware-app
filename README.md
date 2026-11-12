# DriveAware - app

Android app that watches the driver through the front camera and warns about drowsiness and
distraction. CS663 Computer Vision, Project 2 (group: Swathi, Mahitha, Bindu, Vanessa).
Model development code: [`driveaware-models`](../driveaware-models).

📄 Documentation Report PDF: _link after Canvas upload_ · 🎬 Demo video: _YouTube link_

## Architecture 

```
Front camera (CameraX 640×480, live) ──► Model C: MediaPipe Face Landmarker   [on device, pretrained]
                                           │ 478 landmarks · blendshapes · head-pose matrix
                                           ▼
                           Signals: EAR, MAR + jawOpen, yaw/pitch/roll
                                           ▼
                Temporal rules: long closure · PERCLOS 60 s · yawn · nod · gaze-away
                                           │
   dynamic upper-body crop ─► Model A: EfficientNet-Lite0 .tflite   [on device, fine-tuned]
                              phone_use / drinking / smoking / face_touch / safe_driving
                                           ▼
                 Fusion → alert level 1–3 (Level 3 fires locally first)
                                           │ keyframe + 10 s signal summary
                                           ▼
          Cloud Run FastAPI /verify ─► Model B: fine-tuned Gemini 3.5 Flash on Vertex AI  [cloud]
          authority rule · 30 s cooldown · JSON validation · template fallback
                                           ▼
        Chime / spoken message / vibration / red flash · event log · trip summary
```

| Model | What | Where | Customization | Output → next step |
|---|---|---|---|---|
| A | EfficientNet-Lite0 activity classifier | phone | fine-tuned (team + public data) | app class + conf → fusion |
| B | Gemini 3.5 Flash (`gemini-3.5-flash`) | Vertex AI endpoint via Cloud Run | supervised fine-tuned (~250 examples) | event/confirmed/severity/message/action → alert |
| C | MediaPipe Face Landmarker | phone | pretrained | landmarks/blendshapes/pose → rules |

## Repo layout
```
android/            Kotlin + Compose app (CameraX, MediaPipe Tasks)
  app/src/main/java/edu/csueb/driveaware/
    signals/        FaceSignals (EAR/MAR/pose), TemporalEngine, Calibration, Thresholds   ← pure Kotlin, unit-tested
    vision/         FaceLandmarkerHelper (Model C)
    data/           EventLogger (CSV for match_events.py), SettingsStore, AlertManager, BackendClient
    ui/             MonitorViewModel (fusion, modes)
    MainActivity.kt Home · Calibration · Monitoring · Settings · Trip Summary
backend/            FastAPI for Cloud Run: /verify, /summary, /healthz
tests/scripts/      Vanessa's scripted test-session CSVs (S01.csv … S08.csv)
reports/            weekly progress reports
docs/               architecture diagram, screenshots for the report
```

## Build the app
1. Android Studio (Koala or newer) → *Open* `android/`.
2. `bash android/download_assets.sh` (fetches `face_landmarker.task`). Copy `model_a_v1.tflite`
   from Drive → `Models/` into `android/app/src/main/assets/` when it exists.
3. Put the backend URL in `android/local.properties`… or `~/.gradle/gradle.properties`:
   `backendUrl=https://driveaware-backend-xxxx.run.app` (empty = offline/local alerts only).
4. Run on the Pixel 6 (USB debugging on). Unit tests: `./gradlew test`.

## Backend
```bash
cd backend && python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt -r requirements-dev.txt && pytest -q
GCP_PROJECT=driveaware-cs663-swathi GEMINI_MODEL=<tuned-endpoint> bash deploy.sh   # default: gemini-3.5-flash, Vertex location "us"
```

## Status (what this first build includes)
- ✅ Face Landmarker live stream, EAR/MAR/jawOpen/head pose, blink + long closure (L2→L3 escalation),
  rolling 60 s PERCLOS, yawn counter, nod, gaze-away timer (mirror checks < 2 s don't alert)
- ✅ 10 s calibration (median baseline, "face not found" recovery), debug overlay, settings sliders,
  ablation mode switch, event-log CSV export, tiered alerts, offline template messages
- ✅ Backend: schema, authority rule, cooldown, template fallback (tests pass); Cloud Run Dockerfile + deploy
- ⏳ Model A integration (MediaPipe Image Classifier every 3rd frame on the dynamic crop, 70 %/2 s rule,
  "hand occupied"), sunglasses check + degraded mode, fatigue score, "force Model A output" switch,
  Alert screen polish, trip summary via `/summary`

## Project 2 guidelines checklist
_Vanessa: turn every guideline requirement into a checklist here (task #5)._
