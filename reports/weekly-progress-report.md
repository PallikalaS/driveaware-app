Write the weekly progress report from closed Issues and commits, with each person's finished work. Commit to reports/.

**DriveAware – Weekly Progress Report**
Reporting period: [11/3/26 – 11/12/26]


**Swathi Pallikala – Completed Work**

- Created the driveaware-app and driveaware-models repos: layout, README skeletons, .gitignore, Issue templates; add collaborators.
- Google Cloud project: billing, budget alert, and Vertex AI, Cloud Run, Cloud Build, Artifact Registry and Cloud Storage APIs.
- Storage bucket for keyframes and JSONL; least-privilege service account for the backend; IAM access for teammates.
- Confirmed the tunable Gemini Flash model ID, region and dataset limits; record them in the models README.
- Frame-extraction script: 1–2 fps sampling, crop, 224×224 resize, near-duplicate removal, pre-filled labels.csv.
- Crop-comparison script: center crop vs dynamic upper-body crop from face landmarks.
- Label mapping from DMD or State Farm into 10 subclasses and 5 app classes, with a subject-based split.
- Android project from the MediaPipe Face Landmarker sample: CameraX 640×480, front camera, live-stream mode.
- Signal code: EAR, MAR + jawOpen blendshape, yaw/pitch/roll from the transformation matrix.
- Temporal logic: blink and long-closure detection, rolling 60 s PERCLOS, yawn counter, nod detector, gaze-away timer.
- 10-second calibration screen for each driver's EAR baseline and head pose.
- Debugged overlay and event-log export to CSV for testing.
- Sent the APK to Vanessa for threshold tuning.
- System-test matching script: recall, precision, false alarms per 10 min, detection delay.


**Mahitha Rangineni – Completed Work**

- Registered at dmd.vicomtech.org and requested DMD access (can take days). Note the license (CC BY-NC-ND 4.0, academic use only) for the Data Documentation.
- Downloaded the State Farm Distracted Driver dataset from Kaggle as a fallback in case DMD access is slow.
- Bought or borrowed the dashboard phone mount and props: a cup, a bottle, and a pen or unlit prop for smoking.
- Sent GitHub username to Swathi.
- Created the DriveAware/ Google Drive folder with all subfolders (see Notes tab) and shared it with all four as editors.
- Decided the data source: DMD if the download works today, otherwise State Farm. Tell Swathi so the label mapping matches.
- Recorded own clips first and upload them so Swathi can test her scripts on real footage.

  
**Vanessa Rodriguez – Completed Work**
- Sent GitHub username to Swathi.
- On the test Android phone, turned on Developer options and USB debugging. Sent Swathi the phone model and Android version.
- Got the team to agree on the two held-out test subjects (suggested: Mahitha and Vanessa) and posted it on Discord.
- Available for recording slot with Mahitha.
- Wrote 8 test sessions of 5–7 min (about 70 events), one CSV per session in the event CSV format.
- Covered every behavior at least 6 times; add 5+ quick mirror checks; at least 8 min of normal driving in total.
- Spread conditions: day-none, day-clear, day-sun, night-none, night-sun; two mount angles; partial occlusion. Commit CSVs to tests/scripts/.
- Installed Swathi's first APK, try every screen, file each problem as an Issue (screenshot, phone model, steps).


**Bindu Tekumudi – Completed Work**
- Sent GitHub username to Swathi.
- Drafted the one-page labeling guide using the Gemini label-sheet words (see Notes tab).
- Finished labeling_guide.md and committed it to the model repo. Words must match the schema exactly.
- Included the authority rule: measured-signal triggers (eye closure ≥ 1 s, PERCLOS, nodding) are never false_alarm; false_alarm only for ambiguous visual events.
- Included severity rules with numbers and 8–10 worked examples.
- Started the report outline in Report/ with every section from the Project 2 guidelines.


**Outstanding Work - List important tasks that are still in progress or not started.**


**Swathi Pallikala – In progress or not started**

In progress

- FastAPI backend (/verify, /summary, schema, Dockerfile) deployed to Cloud Run with the service account.
- Gemini label-sheet builder: replays clips, detects trigger events, saves keyframes, writes signal summaries and split to a Google Sheet.
- Settings screen for every threshold, sensitivity and gaze limit, saved on device.
- Fusion engine with alert levels 1–3: chime, TextToSpeech, vibration, red flash; Level 3 fires locally first.
- Wire the tuned Gemini endpoint into the backend: prompt, JSON validation, authority rule, 30 s cooldown.
- App-to-backend client: keyframe + 10 s signal summary, timeout, retry, offline mode with template messages.
- Recovery states: Face not found, Eye tracking limited: sunglasses, Offline: local alerts only.
- Test switches: ablation mode and "force Model A output".

Not started

- Model Maker training notebook for EfficientNet-Lite0: settings cell, augmentation, early stopping, curves, TFLite export.
- Baselines: zero-shot CLIP ViT-B/32 and frozen-backbone EfficientNet-Lite0.
- Fallback path: Keras MobileNetV3-Small + TFLite converter.
- Sunglasses check (brightness + Laplacian variance, 3 s hold) and degraded mode.
- Fatigue score combining PERCLOS, yawns, nods and face-touch counts over time windows.
- Model A evaluation script: metrics, confusion matrices, breakdown by condition, success/failure grid.
- Sheet → JSONL converter in Vertex AI format with schema validation of every row.
- Upload keyframes and train.jsonl / val.jsonl to the bucket.
- Launch the Vertex AI supervised tuning job and monitor it.
- Integrate model_a_v1.tflite with the MediaPipe Image Classifier, every 3rd frame on the dynamic crop.
- Activity rules: confidence > 0.6 in 70% of frames over 2 s, "hand occupied" rule, face-touch counter.
- Base-vs-tuned Gemini evaluation script.
- All 5 screens: Home, Calibration, Monitoring, Alert, Trip Summary.
- Apply Vanessa's final thresholds and send the final APK.
- Send Bindu spec notes for Model C, the app screens and the Cloud Run backend.


**Mahitha Rangineni – In progress or not started**

In progress

- Book a 45-minute recording slot with each member for Mon–Tue.
- Full set, daylight, no glasses, 1–2 min each: safe, phone_ear, texting, phone_in_hand, drinking_cup, drinking_bottle, smoking, eye_rub, face_scratch, hand_forehead.
- Short set, 30 s each, in day-clear, day-sun, night-none and night-sun: safe, texting, drinking_cup, eye_rub. Skip clear glasses for anyone without a pair.
- Record all four members. Held-out members' files go to Team_test_only/, everyone else's to Team_raw/.
- Record one 2-minute drowsy-acting clip with a non-held-out member and write its event CSV (script in the doc). Upload to Team_validation/.

Not started

- Open 3 random files per person and check that face, both hands and lap are in frame.
- Run Swathi's crop-comparison script on 3–4 clips (include texting held low). Pick the crop that keeps hands and objects in view; post 3–4 example images on Discord.
- Run Swathi's frame-extraction script on all clips (public dataset + team).
- Start labeling: fill or fix the subclass column in labels.csv for every team frame.
- Finish labels.csv. Spot-check 1 in 20 frames per class; delete blurry frames or frames before the activity starts.
- Check the split column: held-out members are test only.
- Commit labels.csv and per-class counts to the model repo. Images stay in Drive.
- Open Swathi's training notebook in Colab with a GPU and start the first run with the starting values.
- Log every run in model_a_runs.csv. Change one setting at a time only if validation accuracy stalls. Pick the best run by validation score, never test score.
- Run the two baselines in the same notebook: zero-shot CLIP and frozen-backbone EfficientNet-Lite0.
- Save loss and accuracy curves and the confusion matrix to Results/.
- Double-check 50 of Bindu's Gemini labels against the labeling guide and fill checked_by.
- Put model_a_v1.tflite in Models/ and commit the notebook with outputs. Tell Swathi.
- Write the Data Documentation: sources, collection, examples per class, format, labels, preprocessing, split, limitations and biases.
- Draw the final system architecture diagram (start from the proposal's; add settings screen, event log and offline mode).
- Ask each member for their slides by Fri noon.
- Cover Model B's data in the Data Documentation too: number of Gemini examples, format, labels and split (numbers from Bindu).


**Vanessa Rodriguez – In progress or not started**

In progress

- Record 3 validation clips (~3 min) with a non-held-out member and write their event CSVs. Upload to Team_validation/.
- Pick 100 sunglasses and 100 no-sunglasses eye-region frames from non-held-out clips; list them in sunglasses_frames.csv.
- Write the weekly progress report from closed Issues and commits, with each person's finished work. Commit to reports/.

Not started

- Tune thresholds in the app's settings screen on validation clips only (eye closure, PERCLOS, yawn, head angles, gaze time, sunglasses).
- Commit thresholds.csv with starting and final values; send the final values to Swathi.
- With Bindu, rate 30 Gemini messages for clarity.
- Install the complete APK and re-test every screen; close fixed Issues and file new ones.
- Screenshot each of the 5 screens and each alert level for the report's GUI section; send to Bindu.


**Bindu Tekumudi – In progress or not started**

In progress

- Base-Gemini check in Vertex AI Studio: 5 frames + signal line with the proposal's system instruction. Screenshot replies and note failures. Save to Results/gemini_base_check/.

Not started

- Be available for recording slot with Mahitha.
- Check type counts: at least 25 false_alarm rows and 15 of each other event type. Tell Swathi if a type is short.
- Label about 250 rows in the Gemini label sheet: event, confirmed, severity, message, action, labeler.
- Check the split: about 170 train / 40 val / 40 test, by person. Test rows only from held-out members.
- Send the sheet to Mahitha for the 50-row check, settle disagreements, then tell Swathi it is final.
- Model A evaluation: run the evaluation script on the test set. Accuracy, per-class precision/recall/F1, macro-F1, both confusion matrices.
- Compare CLIP zero-shot, frozen backbone and fully fine-tuned on the same test set.
- Grid of correct and failed test images with a reason per failure; accuracy by lighting and eyewear.
- Screenshot the Gemini tuning loss curves once Swathi shares them.
- Model B evaluation: base vs tuned Gemini on the 40 test rows (event accuracy, macro-F1, severity, false-alarm rejection, valid JSON, message length, latency).
- With Vanessa, rate 30 tuned-model messages for clarity from 1 to 5.
- Draft the Model Results section and a spec table for each model.
- Pick at least 3 success and 3 failure examples for tuned Gemini, with a reason for each failure.
- Draft Response to proposal feedback, Installation/Setup and Reproducing the System from Swathi's notes.
- Draft Project Overview, Model C spec table, Mobile Application GUI + specs and Cloud/Backend specs.
