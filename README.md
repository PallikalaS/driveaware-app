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
| A | EfficientNet-Lite0 activity classifier (MobileNetV3-Small fallback) | phone | fine-tuned (State Farm + team clips) | app class + conf → fusion |
| B | Gemini 3.5 Flash (`gemini-3.5-flash`) | Vertex AI endpoint via Cloud Run | supervised fine-tuned (~250 examples) | event/confirmed/severity/message/action → alert |
| C | MediaPipe Face Landmarker | phone | pretrained | landmarks/blendshapes/pose → rules |

## Repo layout
```
android/            Kotlin + Compose app (CameraX, MediaPipe Tasks)
  app/src/main/java/edu/csueb/driveaware/
    signals/        pure Kotlin, unit-tested: FaceSignals (EAR/MAR/pose), TemporalEngine (rules),
                    Calibration, Thresholds, Sunglasses, ActivityRules (Model A rules), FatigueScore,
                    CropGeometry (Model A crop, same as the Python crop)
    vision/         FaceLandmarkerHelper (Model C), ModelAClassifier (Model A)
    data/           EventLogger (CSV for match_events.py), SettingsStore, AlertManager, BackendClient
    ui/             MonitorViewModel (fusion, modes, alerts, trip summary)
    MainActivity.kt Home · Calibration · Monitoring · Alert · Trip Summary · Settings · Test options
backend/            FastAPI for Cloud Run: /verify (event check), /summary (trip note), /health
tests/scripts/      Vanessa's system-test session scripts (S01.csv … S08.csv)
tests/validation/   validation clip scripts (V01.csv … V03.csv) for threshold tuning
docs/spec_notes.md  specs for the report: models, rules, screens, backend, measured numbers
reports/            weekly progress reports
```

## Build the app
1. Android Studio → *Open* `android/`. Settings → Build Tools → Gradle → **Gradle JDK = 21**
   (Gradle 8.9 does not run on the bundled JDK 25).
2. `bash android/download_assets.sh` (fetches `face_landmarker.task` into `app/src/main/assets/`).
   Copy `model_a_v1.tflite` from Drive → `Models/` into the same folder when it exists; without it
   the app runs with Model A off (Home shows ✗ Model A).
3. Backend URL in `android/local.properties` (not committed):
   `backendUrl=<Cloud Run URL>` (ask Swathi; kept out of this public repo because the endpoint calls a paid API). Empty = offline, local alerts only.
4. Run on the emulator (Pixel 6 API 34, front camera = Webcam0) or a phone. Command-line APK:
   `cd android && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean assembleDebug`
   → `app/build/outputs/apk/debug/app-debug.apk`. Released APKs: Drive → `Models/driveaware-vX.Y.apk`.
5. Unit tests: `./gradlew test` (rules, sunglasses, activity rules, fatigue score, crop geometry).

## Backend
```bash
cd backend && python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt -r requirements-dev.txt && pytest -q
export CLOUDSDK_ACTIVE_CONFIG_NAME=driveaware   # gcloud config for this project
GCP_PROJECT=driveaware-cs663-swathi bash deploy.sh                       # base gemini-3.5-flash, Vertex "us"
GCP_PROJECT=driveaware-cs663-swathi GEMINI_MODEL=<tuned endpoint> bash deploy.sh   # after tuning
curl -s "$(gcloud run services describe driveaware-backend --region us-central1 --format='value(status.url)')/health"
```
Cloud Run (us-central1, 0–3 instances, 512 MiB) runs as a service account with only Vertex AI User;
no keys on the phone. `/verify` applies the authority rule (measured closures / PERCLOS / nods are never
dismissed), a 30 s cooldown per event type and a template fallback; `/summary` asks the base model for a
2–3 sentence trip note from counts only. Undeploy after grading: `gcloud run services delete driveaware-backend --region us-central1`.

## How to use the app
1. **Calibrate** (10 s looking at the road), then **Start trip** (optional session ID S01… for test runs).
2. Alerts: Level 1 beep · Level 2 beep + vibration + voice · Level 3 alarm + strong vibration +
   full-screen red flash. Tap **I'M OK** to silence and clear an alert.
3. **End trip** → summary: fatigue chart, alerts by level, Gemini-written note, **Export event log** (CSV).
4. **Test options**: ablation mode (rules / rules + Model A / full), debug overlay, force Model A output.
5. **Settings**: every threshold as a slider (saved on the phone).

## Status (v0.8, Nov 13)
- ✅ Model C + rules: blink, long closure (no alert from a yawn), PERCLOS, yawn, nod, gaze-away
  (mirror checks < 2 s don't alert), calibration, sunglasses degraded mode (eye/cheek brightness), fatigue score
- ✅ Model A path: classifier wrapper on the upper-body crop every 3rd frame, 70 % / 2 s rule, hand-occupied,
  face-touch counter, force-output test switch (waiting for `model_a_v1.tflite`)
- ✅ All 5 proposal screens, recovery states (face not found after 3 s, sunglasses, offline), event-log CSV
- ✅ Backend live on Cloud Run with base Gemini 3.5 Flash: /verify, /summary (Gemini trip note), /health
- ⏳ Tuned Gemini endpoint, final thresholds from V01–V03, Model A v1, system-test results

## Project 2 guidelines checklist

This checklist tracks the Project 2 course requirements and final deliverables. The full course guidelines are the source of truth. Check each item only after the work is completed and verified.

### 1. Mobile Application Requirements

- [ ] Build and demonstrate a real Android mobile application using live camera input, not a Colab-only application. (Swathi)
- [ ] Allow the user to start and control the monitoring session. (Swathi)
- [ ] Show that the application is processing camera input and displaying results. (Swathi)
- [ ] Provide useful results and alerts that allow the user to take action. (Swathi)
- [ ] Handle situations where the system cannot determine an answer, including face not found, limited eye tracking, and offline operation. (Swathi)
- [ ] Include meaningful user interaction and recovery options, not only object or activity recognition. (Swathi)
- [ ] Document where each model runs: on the phone or in the cloud. (Swathi and Bindu)

### 2. Three-Model Architecture

- [ ] Integrate all three models into one working system. (Swathi)
- [ ] Model A: document the trained or fine-tuned EfficientNet-Lite0 activity classifier. (Mahitha and Bindu)
- [ ] Model B: document the fine-tuned Gemini multimodal model and its responsibility. (Bindu and Swathi)
- [ ] Model C: document the pretrained MediaPipe Face Landmarker and its responsibility. (Bindu and Swathi)
- [ ] Explain why each model is needed and what its specific job is. (Bindu)
- [ ] Document each model's exact name, purpose, input, output, location, customization, and next step. (Bindu)
- [ ] Include the final system architecture diagram in the documentation report. (Mahitha and Bindu)
- [ ] Show the camera input, preprocessing, all three models, information passed between components, backend, application logic, and user output in the architecture diagram. (Mahitha and Swathi)

### 3. Data Collection and Documentation

- [ ] Document the data sources used for Model A and Model B. (Mahitha)
- [ ] Document how the data was collected and how many examples were used. (Mahitha and Bindu)
- [ ] Document the data format, labels, preprocessing, and train/validation/test splits for each trained or fine-tuned model. (Mahitha)
- [ ] Document dataset limitations, possible biases, and other relevant restrictions. (Mahitha)
- [ ] Include the team's application-specific data. (Mahitha)
- [ ] Keep held-out test subjects separate from training, validation, and threshold tuning. (Everyone)
- [ ] Make sure final test examples are not used to train or fine-tune either model. (Everyone)
- [ ] Include the Data Documentation in the final report. (Mahitha and Bindu)

### 4. Model A and Model B Evaluation

- [ ] Evaluate Model A using the held-out test data. (Bindu)
- [ ] Report Model A accuracy, precision, recall, F1, macro-F1, and confusion matrices. (Bindu)
- [ ] Include Model A training and validation loss and accuracy curves. (Mahitha and Bindu)
- [ ] Compare the fully fine-tuned Model A against the CLIP zero-shot and frozen-backbone EfficientNet-Lite0 baselines using the same test set. (Bindu)
- [ ] Include successful and failed Model A examples with explanations. (Bindu)
- [ ] Include Model A results broken down by relevant conditions, including lighting and eyewear. (Bindu)
- [ ] Evaluate Model B using held-out test examples. (Bindu)
- [ ] Compare base Gemini against fine-tuned Gemini. (Bindu)
- [ ] Report Model B event accuracy, macro-F1, severity accuracy, confusion matrix, false-alarm rejection rate, valid-JSON rate, message length, and latency. (Bindu)
- [ ] Include Gemini tuning results and loss curves. (Swathi and Bindu)
- [ ] Include successful and failed Gemini examples with explanations. (Bindu)

### 5. Full-System Testing and Evaluation

- [ ] Test the complete application using the eight planned sessions and held-out members. (Vanessa)
- [ ] Record the sessions in a parked car or with the subject as a non-driving passenger. (Vanessa)
- [ ] Keep the application and event logging enabled during each session. (Vanessa)
- [ ] Export each event log to Test_sessions/ and run the matching script against the corresponding session CSV. (Vanessa and Swathi)
- [ ] Report precision and recall for each behavior. (Vanessa)
- [ ] Report false alarms per 10 minutes of normal driving. (Vanessa)
- [ ] Report detection delay. (Vanessa)
- [ ] Measure on-device FPS and battery use. (Swathi)
- [ ] Measure Gemini round-trip time. (Vanessa and Swathi)
- [ ] Perform ablation testing on three sessions using rules only, rules plus Model A, and the full system. (Vanessa)
- [ ] Perform error-injection testing using the force Model A output option and record whether the rules or Gemini catch the incorrect output. (Vanessa)
- [ ] Record the first stage responsible for every missed or false alert. (Vanessa)
- [ ] Include the failure-source bar chart. (Vanessa)
- [ ] Explain error propagation, the components that fail most often, system response time, and how the system handles incorrect model outputs. (Vanessa and Bindu)
- [ ] Include system-testing results, successful examples, failed examples, and limitations in the report. (Vanessa and Bindu)

### 6. Real-World Camera Testing

- [ ] Test relevant lighting conditions, including daytime and nighttime. (Vanessa)
- [ ] Test relevant eyewear conditions, including no glasses, clear glasses, and sunglasses. (Vanessa)
- [ ] Test both camera mounting angles. (Vanessa)
- [ ] Test partial face occlusions, including a hand, hat brim, and mask. (Vanessa)
- [ ] Include previously unseen held-out subjects and examples. (Vanessa)
- [ ] Show all three alert levels during testing and the demonstration. (Vanessa)
- [ ] Include at least one honest system failure in the demonstration and explain it. (Vanessa)
- [ ] Discuss the conditions where the system works and where it has limitations. (Vanessa and Bindu)

### 7. GitHub, Project Management, and Weekly Progress

- [ ] Maintain the mobile application repository, driveaware-app. (Swathi)
- [ ] Maintain the model development repository, driveaware-models. (Swathi)
- [ ] Include source code, training and evaluation code, data preparation code, and technical documentation in the appropriate repositories. (Swathi)
- [ ] Maintain GitHub Issues and the Project board with task owners and statuses. (Vanessa)
- [ ] Maintain meaningful commit history showing each member's contributions. (Everyone)
- [ ] Keep weekly progress reports showing completed work, outstanding work, and links to relevant Issues or commits. (Everyone; Vanessa coordinates the report)
- [ ] Make each member's individual contributions identifiable. (Everyone)
- [ ] Include setup and reproduction instructions in the repositories. (Swathi)

### 8. Final Documentation Report

- [ ] Project Overview. (Bindu)
- [ ] Response to proposal feedback. (Bindu)
- [ ] System Architecture. (Mahitha and Bindu)
- [ ] Installation and Setup. (Swathi and Bindu)
- [ ] Data Documentation. (Mahitha and Bindu)
- [ ] Model A Specifications and Results. (Mahitha and Bindu)
- [ ] Model B Gemini Fine-Tuning Specifications and Results. (Bindu and Swathi)
- [ ] Additional Model Specifications for Model C. (Bindu and Swathi)
- [ ] Mobile Application GUI and Specifications, including screenshots. (Vanessa and Bindu)
- [ ] Cloud and Backend Specifications. (Swathi and Bindu)
- [ ] Testing and Evaluation. (Vanessa)
- [ ] Results Discussion, including successes, failures, limitations, and system performance. (Vanessa and Bindu)
- [ ] Instructions for Reproducing the System. (Swathi and Bindu)
- [ ] Include the required data, model, and system evaluation results, graphs, screenshots, and failure examples. (Everyone)
- [ ] Collect all team sections into one report draft and review it. (Bindu)
- [ ] Export the final Documentation Report as a PDF and upload it to Canvas. (Bindu)
- [ ] Link the final report from the driveaware-app README. (Bindu)

### 9. Final Presentation

- [ ] Problem. (Mahitha)
- [ ] Users. (Mahitha)
- [ ] Architecture. (Mahitha)
- [ ] Models. (Mahitha)
- [ ] Data. (Mahitha)
- [ ] Training and Fine-Tuning. (Mahitha)
- [ ] Mobile Application. (Mahitha)
- [ ] Evaluation. (Mahitha and Vanessa)
- [ ] Results. (Mahitha and Vanessa)
- [ ] Failures. (Mahitha and Vanessa)
- [ ] Lessons Learned. (Mahitha)
- [ ] Add the final results from Bindu and Vanessa. (Mahitha)
- [ ] Explain the project sections to the team and prepare for the presentation and demo. (Swathi)
- [ ] Rehearse the presentation with the team and submit the final slides. (Mahitha)

### 10. Demonstration Video

- [ ] Use the actual Android application with live camera input. (Vanessa)
- [ ] Demonstrate held-out members and previously unseen examples. (Vanessa)
- [ ] Show all three alert levels. (Vanessa)
- [ ] Show sunglasses mode and its behavior. (Vanessa)
- [ ] Show offline operation. (Vanessa)
- [ ] Show the trip summary. (Vanessa)
- [ ] Include one honest failure. (Vanessa)
- [ ] Do not show notebooks or source code in the demonstration. (Vanessa)
- [ ] Edit and narrate the final demonstration video. (Vanessa)
- [ ] Upload the video to YouTube as Unlisted. (Vanessa)
- [ ] Add the video link to the README and final report. (Vanessa)

### 11. Final Repository and Project Handover

- [ ] Include the final Android application source code repository URL. (Swathi)
- [ ] Include the model development repository URL, including Model A training, Gemini fine-tuning, evaluation, and data preparation code. (Swathi)
- [ ] Include setup and reproduction notes in both repository READMEs. (Swathi)
- [ ] Fix bugs reported as GitHub Issues during system testing and release the final APK. (Swathi)
- [ ] Confirm the final report and demonstration video links are included in the app repository README. (Bindu and Vanessa)
- [ ] After grading, undeploy the tuned Gemini endpoint and Cloud Run service. (Swathi)

### 12. Requirements to Verify

The following items need to be checked against the course guidelines and the team's completed work. Do not assume they are complete just because related tasks are listed.

- [ ] Confirm that the project has instructor approval if approval is required. (Team)
- [ ] Confirm that the final architecture diagram is included in both the proposal and final report. (Mahitha and Bindu)
- [ ] Explain why each image-processing and preprocessing step is used, including cropping, resizing, and temporal processing. (Swathi and Mahitha)
- [ ] Confirm that every model's input, output, location, customization, and next step are documented. (Bindu)
- [ ] Confirm that data limitations, biases, sources, and splits are documented for both trained or fine-tuned models. (Mahitha and Bindu)
- [ ] Confirm that held-out test data was not used for training, fine-tuning, or threshold tuning. (Everyone)
- [ ] Confirm that the report explains how the application handles uncertain results, incorrect predictions, and component failures. (Vanessa and Bindu)
- [ ] Confirm that the final report includes system response time, FPS, battery use, and Gemini latency where required. (Vanessa, Swathi, and Bindu)
- [ ] Confirm that the final results include both successful and failed examples, not only successful demonstrations. (Everyone)
