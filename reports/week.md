Write the weekly progress report from closed Issues and commits, with each person's finished work. Commit to reports/.

**DriveAware – Weekly Progress Report**
Reporting period: [11/2/26 – 11/6/26]


**Swathi Pallikala – Completed Work**

- Created the driveaware-app and driveaware-models repos: layout, README skeletons, .gitignore, Issue templates; add collaborators.
- Google Cloud project: billing, budget alert, and Vertex AI, Cloud Run, Cloud Build, Artifact Registry and Cloud Storage APIs.
- Storage bucket for keyframes and JSONL; least-privilege service account for the backend; IAM access for teammates.
- Confirmed the tunable Gemini Flash model ID, region and dataset limits; record them in the models README.
- Frame-extraction script: 1–2 fps sampling, crop, 224×224 resize, near-duplicate removal, pre-filled labels.csv.

**Mahitha Rangineni – Completed Work**

- Registered at dmd.vicomtech.org and requested DMD access (can take days). Note the license (CC BY-NC-ND 4.0, academic use only) for the Data Documentation.
- Downloaded the State Farm Distracted Driver dataset from Kaggle as a fallback in case DMD access is slow.
- Bought or borrowed the dashboard phone mount and props: a cup, a bottle, and a pen or unlit prop for smoking.
- Sent GitHub username to Swathi.

  
**Vanessa Rodriguez – Completed Work**
- Sent GitHub username to Swathi.
- On the test Android phone, turned on Developer options and USB debugging. Sent Swathi the phone model and Android version.
- Got the team to agree on the two held-out test subjects (suggested: Mahitha and Vanessa) and posted it on Discord.
- Available for recording slot with Mahitha.

**Bindu Tekumudi – Completed Work**
- Sent GitHub username to Swathi.
- Drafted the one-page labeling guide using the Gemini label-sheet words (see Notes tab).
- Finished labeling_guide.md and committed it to the model repo. Words must match the schema exactly.
- Included the authority rule: measured-signal triggers (eye closure ≥ 1 s, PERCLOS, nodding) are never false_alarm; false_alarm only for ambiguous visual events.


**Outstanding Work - List important tasks that are still in progress or not started.**


**Swathi Pallikala – In progress or not started**

In progress

- Crop-comparison script: center crop vs dynamic upper-body crop from face landmarks.
- Label mapping from DMD or State Farm into 10 subclasses and 5 app classes, with a subject-based split.
- Android project from the MediaPipe Face Landmarker sample: CameraX 640×480, front camera, live-stream mode.
- Signal code: EAR, MAR + jawOpen blendshape, yaw/pitch/roll from the transformation matrix.
- Temporal logic: blink and long-closure detection, rolling 60 s PERCLOS, yawn counter, nod detector, gaze-away timer.
- 10-second calibration screen for each driver's EAR baseline and head pose.
- Debugged overlay and event-log export to CSV for testing.
- Sent the APK to Vanessa for threshold tuning.
- System-test matching script: recall, precision, false alarms per 10 min, detection delay.

Not started

- FastAPI backend (/verify, /summary, schema, Dockerfile) deployed to Cloud Run with the service account.
- Gemini label-sheet builder: replays clips, detects trigger events, saves keyframes, writes signal summaries and split to a Google Sheet.
- Settings screen for every threshold, sensitivity and gaze limit, saved on device.
- Fusion engine with alert levels 1–3: chime, TextToSpeech, vibration, red flash; Level 3 fires locally first.
- Wire the tuned Gemini endpoint into the backend: prompt, JSON validation, authority rule, 30 s cooldown.
- App-to-backend client: keyframe + 10 s signal summary, timeout, retry, offline mode with template messages.
- Recovery states: Face not found, Eye tracking limited: sunglasses, Offline: local alerts only.
- Test switches: ablation mode and "force Model A output".


**Mahitha Rangineni – In progress or not started**

In progress

- Created the DriveAware/ Google Drive folder with all subfolders (see Notes tab) and shared it with all four as editors.
- Decided the data source: DMD if the download works today, otherwise State Farm. Tell Swathi so the label mapping matches.
- Recorded own clips first and upload them so Swathi can test her scripts on real footage.

Not started

- Book a 45-minute recording slot with each member for Mon–Tue.
- Full set, daylight, no glasses, 1–2 min each: safe, phone_ear, texting, phone_in_hand, drinking_cup, drinking_bottle, smoking, eye_rub, face_scratch, hand_forehead.
- Short set, 30 s each, in day-clear, day-sun, night-none and night-sun: safe, texting, drinking_cup, eye_rub. Skip clear glasses for anyone without a pair.
- Record all four members. Held-out members' files go to Team_test_only/, everyone else's to Team_raw/.
- Record one 2-minute drowsy-acting clip with a non-held-out member and write its event CSV (script in the doc). Upload to Team_validation/.


**Vanessa Rodriguez – In progress or not started**

In progress

- Wrote 8 test sessions of 5–7 min (about 70 events), one CSV per session in the event CSV format.
- Covered every behavior at least 6 times; add 5+ quick mirror checks; at least 8 min of normal driving in total.
- Spread conditions: day-none, day-clear, day-sun, night-none, night-sun; two mount angles; partial occlusion. Commit CSVs to tests/scripts/.
- Installed Swathi's first APK, try every screen, file each problem as an Issue (screenshot, phone model, steps).

Not started

- Record 3 validation clips (~3 min) with a non-held-out member and write their event CSVs. Upload to Team_validation/.
- Pick 100 sunglasses and 100 no-sunglasses eye-region frames from non-held-out clips; list them in sunglasses_frames.csv.
- Write the weekly progress report from closed Issues and commits, with each person's finished work. Commit to reports/.


**Bindu Tekumudi – In progress or not started**

In progress

- Included severity rules with numbers and 8–10 worked examples.
- Started the report outline in Report/ with every section from the Project 2 guidelines.

Not started

- Base-Gemini check in Vertex AI Studio: 5 frames + signal line with the proposal's system instruction. Screenshot replies and note failures. Save to Results/gemini_base_check/.

