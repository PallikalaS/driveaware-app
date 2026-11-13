# DriveAware spec notes (for the report)

Source of truth for the report's Model C spec table, Mobile Application GUI + specs,
Cloud/Backend specs, and Installation / Reproducing sections (Bindu #21, #22).
Numbers come from the code on `main` (v0.8.0) and from measured runs; "measured" says where.

## 1. System at a glance

```
Front camera 640x480 ──► Model C: MediaPipe Face Landmarker (every frame, on phone)
                           │  478 landmarks, 52 blendshapes, head-pose matrix
                           ▼
                      Rules engine (on phone): EAR/MAR, blinks, PERCLOS, yawn, nod, gaze,
                      sunglasses check, fatigue score
                           │
Upper-body crop 224 ──► Model A: activity classifier (every 3rd frame, on phone)
                           │  safe_driving / phone_use / drinking / smoking / face_touch
                           ▼
                      Alert level 1-3 shown at once (Level 3 never waits for the cloud)
                           │ keyframe (512 px JPEG) + 10 s signal summary
                           ▼
                      Cloud Run backend ──► Model B: Gemini 3.5 Flash (Vertex AI, tuned)
                           │  confirms / dismisses, severity, one-sentence message, action
                           ▼
                      Voice message, alert updated; end of trip: Gemini-written summary
```

## 2. Model C: MediaPipe Face Landmarker (pretrained, not trained by us)

| Item | Value |
|---|---|
| Model | MediaPipe Tasks Face Landmarker, `face_landmarker.task` (float16 bundle) |
| Outputs used | 478 face landmarks, 52 blendshapes (`eyeBlinkLeft/Right`, `jawOpen`), facial transformation matrix |
| Running mode | `LIVE_STREAM`, 1 face, detection / presence confidence 0.5 |
| Input | CameraX front camera, 640x480 RGBA, landscape |
| Delegate | GPU on phones (CPU fallback on error); CPU on the emulator |
| Speed (measured) | Pixel 6, Android 12, GPU: about 24 fps (Vanessa, v0.8 debug overlay). Emulator (Pixel 6 API 34, Mac CPU): 31-42 fps |
| Signals derived | EAR (eye aspect ratio, 6 points per eye), MAR (mouth), yaw / pitch / roll from the matrix (head down = negative pitch), eye-area brightness and sharpness, cheek brightness |

### Rules on Model C signals (defaults; Vanessa tunes them on V01-V03)

| Event | Rule | Level |
|---|---|---|
| Blink | eye closed (EAR < 0.70 x calibrated EAR, or eyeBlink >= 0.50) for < 0.5 s | counted, no alert |
| Long eye closure | closed >= 1.0 s (clock starts after a yawn ends) | 3 (local alarm at once) |
| PERCLOS | share of closed frames over 60 s >= 0.15 (at most once per 30 s) | 2 |
| Yawn | MAR >= 0.60 or jawOpen >= 0.55 held >= 1.5 s; no eye-closure alert while the mouth is open | 1, or 2 from the 3rd yawn in 5 min |
| Nod | head pitch >= 15 degrees below baseline for >= 0.4 s | 2, or 3 if PERCLOS is high |
| Gaze away | yaw > 30 or pitch > 20 degrees off baseline for > 2 s (mirror checks are shorter) | 1 |
| Sunglasses (degraded mode) | eye-area / cheek brightness < 0.6 (or dark and flat) for 3 s | eye rules off; yawn, nod, gaze, activity stay on |
| Same event again | not within 10 s (yawn and nod: 3 s; PERCLOS: 30 s) | |

Calibration: 10 s looking at the road sets each driver's EAR and head-pose baseline.
Fatigue score 0-100: PERCLOS 40 + nods (5 min) 25 + yawns (5 min) 20 + face touches (5 min) 15;
bands < 30 alert, 30-60 tiring, > 60 drowsy. With sunglasses PERCLOS is left out and the rest rescaled.

## 3. Model A: activity classifier (fine-tuned by us)

| Item | Value |
|---|---|
| Base | EfficientNet-Lite0 via MediaPipe Model Maker; fallback MobileNetV3-Small (Keras), used in run01 because Model Maker did not install on Colab |
| Input | 224x224 upper-body crop: width 4 x face width, from 0.6 face heights above the face to 4.5 below, padded square (same code on phone and in training) |
| Classes | safe_driving, phone_use, drinking, smoking, face_touch (10 subclasses map to these) |
| Data | State Farm Distracted Driver (c0, c1-c4, c6, c8) + team clips; test = held-out members only (Mahitha, Vanessa) |
| Baselines | CLIP ViT-B/32 zero-shot; frozen backbone (last layer only) |
| On phone | MediaPipe Image Classifier, every 3rd frame; event when > 0.6 confidence in 70 % of outputs over 2 s |
| Results | run01 (State Farm only) test accuracy: CLIP 0.579, frozen 0.229, fine-tuned 0.260 (domain gap). run02 adds team clips. Final numbers: Results/model_a/ |

## 4. Model B: Gemini 3.5 Flash (fine-tuned by us)

| Item | Value |
|---|---|
| Base model | `gemini-3.5-flash` on Vertex AI, location `us` (Gemini 2.5 Flash was retired on Vertex AI on Oct 20, 2026) |
| Tuning | Vertex AI supervised fine-tuning, location `us`; train / val JSONL from Bindu's label sheet |
| Input | system instruction + one keyframe (JPEG, 512 px wide) + one signal line, e.g. `Signals (last 10 s): trigger=eye_closure_long level=3 perclos=0.22 ...` |
| Output | JSON: event, confirmed, severity, message (<= 20 words), action |
| Settings | thinking level MINIMAL, max 512 output tokens, JSON schema enforced |
| Safety rule (authority) | measured signals (closure >= 1 s, PERCLOS, nods) can never be called false_alarm; closure >= 1 s is always high unless sunglasses; Gemini cannot lower these |
| Evaluation | base vs tuned on held-out test rows: event accuracy, macro-F1, false-alarm precision / recall, severity / action accuracy, valid JSON, latency (eval/gemini_eval.py) |
| Trip summary | base gemini-3.5-flash writes 2-3 sentences from trip counts only (no images) |

## 5. Mobile application (Android, Kotlin, Jetpack Compose)

| Screen | What it shows |
|---|---|
| 1. Home | Start trip, Calibrate, Settings, Test options; readiness chips (Camera, Calibrated, Cloud, Model A); last-trip line |
| 2. Calibration | 10 s countdown looking at the road; sets EAR and head-pose baseline |
| 3. Monitoring | Pulsing status badge (green monitoring, amber fatigue, blue distraction, red critical); Eyes / Head / Activity / Glasses tiles; fatigue bar; spinner while Gemini checks an event; optional debug overlay (fps, EAR, MAR, PERCLOS, pose, Model A) |
| 4. Alert | Banner in amber (fatigue), blue (distraction) or red (Level 3) with the message; "Checked by Gemini" tag; I'M OK button; Level 3 = full-screen red flash until I'M OK |
| 5. Trip Summary | Duration, peak fatigue, fatigue-over-time chart with alert marks, alerts by level, Gemini-written summary, export event log (CSV) |

Alert outputs: Level 1 soft beep; Level 2 beep + short vibration + spoken message (TextToSpeech);
Level 3 loud alarm + strong vibration + full-screen red flash + spoken message.

Recovery states: "Face not found: adjust the phone" after 3 s without a face;
"Eye tracking limited: sunglasses"; "Offline: local alerts only" when the backend can't be reached
(local messages are used, every rule still works).

Test switches (Test options): ablation mode (rules only / rules + Model A / full), debug overlay,
"force Model A output" (simulates a Model A class for error-injection tests).

Settings: every threshold above as a slider, saved on the phone.

Event log (CSV per trip): `t_s, event_type, level, source, mode, model_a_class, model_a_conf,
gemini_event, gemini_confirmed, severity, latency_ms, fps`.

## 6. Cloud backend

| Item | Value |
|---|---|
| Service | FastAPI on Cloud Run, `us-central1`, Python 3.12 container |
| Scaling | 0-3 instances, 512 MiB, 30 s request timeout |
| Endpoints | `POST /verify` (event check), `POST /summary` (trip note), `GET /health` |
| Security | No keys on the phone; Cloud Run runs as a service account with only Vertex AI User + bucket read |
| App side | 4 s timeout, 1 retry, then local message; warm-up call at trip start |
| Cooldown | same event type is sent to Gemini at most once per 30 s per trip |
| Fallback | template message by the labeling-guide rules when Gemini fails or times out |
| Latency (measured, base model) | warm about 1.1 s, cold start 3.4-4 s (curl from a Mac to Cloud Run, Nov 12) |
| Privacy | only one 512 px keyframe per event and numeric signals leave the phone; no video is stored |

## 7. Installation and reproducing

- App: open `android/` in Android Studio (Gradle JDK 21), put `face_landmarker.task` (and
  `model_a_v1.tflite` when ready) in `app/src/main/assets/`, set `backendUrl=` in
  `android/local.properties`, Run. APKs: Drive → Models/.
- Backend: `GCP_PROJECT=driveaware-cs663-swathi bash backend/deploy.sh`.
- Models repo (driveaware-models): `scripts/extract_frames.py` → `notebooks/model_a_training.ipynb`
  (Colab) for Model A; `scripts/build_label_sheet.py` → label in Sheets → `scripts/sheet_to_jsonl.py`
  → `scripts/gemini_tuning.py upload / tune / status` → `eval/gemini_eval.py` for Model B;
  `eval/match_events.py` for system tests. Each script's `--help` has the exact command.
