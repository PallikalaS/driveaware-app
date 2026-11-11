"""Backend decision logic, kept free of FastAPI/Vertex so it is unit-testable.

* build_prompt          – the text part sent with the keyframe (also used to
                          build the fine-tuning JSONL, so train == serve).
* apply_authority_rule  – measured signals can never be dismissed by Gemini.
* Cooldown              – at most one Gemini verdict per trigger per 30 s per trip.
* template_verdict      – offline / timeout / invalid-JSON fallback.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

from .schema import MEASURED_TRIGGERS, GeminiVerdict, SignalSummary, VerifyRequest

SYSTEM_INSTRUCTION = (
    "You are DriveAware's in-car safety checker. You get one dashboard-camera frame of the "
    "driver and a summary of the last 10 seconds of measured face and activity signals. "
    "Decide what is happening and reply ONLY with JSON matching the schema: event, confirmed, "
    "severity, message, action. The message is one calm sentence of 20 words or fewer, spoken "
    "to the driver. Measured signals (long eye closure, PERCLOS, nodding) are facts: never call "
    "them false_alarm. Use false_alarm only for ambiguous visual events such as a mirror check."
)

TRIGGER_TO_EVENT = {
    "eye_closure_long": "drowsy", "perclos_high": "drowsy", "nod": "drowsy",
    "yawn": "early_fatigue", "gaze_away": "distracted_gaze",
    "phone_use": "distracted_phone", "drinking": "drinking", "smoking": "smoking",
    "face_touch": "early_fatigue",
}

PERCLOS_HIGH = 0.15          # labeling_guide.md section 2
GAZE_MEDIUM_S = 4.0          # gaze off road > 4 s -> medium

# Offline / fallback messages. Wording follows labeling_guide.md section 5:
# one calm sentence, <= 20 words, ends with a period, matches the action.
TEMPLATES = {
    # (event, severity) -> (message, action)
    ("drowsy", "high"): ("Your eyes closed for over a second, so please pull over safely and rest.", "pull_over_now"),
    ("drowsy", "medium"): ("You're showing signs of fatigue, so plan a break at the next safe stop.", "take_break_soon"),
    ("early_fatigue", "low"): ("You seem a little tired, so consider a short break soon.", "take_break_soon"),
    ("early_fatigue", "medium"): ("You've yawned several times, so a short break soon would help.", "take_break_soon"),
    ("distracted_phone", "medium"): ("Put the phone down and keep your eyes on the road.", "refocus"),
    ("distracted_phone", "high"): ("Put the phone down and keep your eyes on the road.", "refocus"),
    ("distracted_gaze", "low"): ("Eyes back on the road, please.", "refocus"),
    ("distracted_gaze", "medium"): ("Eyes back on the road, please.", "refocus"),
    ("drinking", "low"): ("Keep at least one hand on the wheel while you drink.", "refocus"),
    ("smoking", "low"): ("Keep your hands and eyes on the road while you smoke.", "refocus"),
}
# A nod is drowsy but the high template mentions eyes; use a nod-specific line.
NOD_MESSAGES = {
    "high": ("Your head dropped forward, so please pull over safely and rest.", "pull_over_now"),
    "medium": ("Your head dropped forward, so consider taking a break soon.", "take_break_soon"),
}


def build_prompt(trigger: str, level: int, s: SignalSummary) -> str:
    """Compact signal line. The SAME function formats the tuning examples."""
    parts = [
        f"trigger={trigger}", f"level={level}",
        f"perclos={s.perclos:.2f}", f"blinks={s.blinks}",
        f"longest_closure_s={s.longest_closure_s:.1f}",
        f"yawns_5min={s.yawns_5min}", f"nods_5min={s.nods_5min}",
        f"gaze_away_s={s.gaze_away_s:.1f}",
        f"yaw={s.yaw_deg:.0f}", f"pitch={s.pitch_deg:.0f}",
        f"model_a={s.model_a_class or 'na'}:{(s.model_a_conf or 0):.2f}",
        f"hand_occupied={'yes' if s.hand_occupied else 'no'}",
        f"sunglasses={'yes' if s.sunglasses else 'no'}",
    ]
    return "Signals (last 10 s): " + " ".join(parts)


def fallback_event(trigger: str, s: SignalSummary) -> str:
    """Event for the template fallback (labeling_guide.md section 3)."""
    if trigger in ("yawn", "face_touch") and s.perclos > PERCLOS_HIGH and not s.sunglasses:
        return "drowsy"
    return TRIGGER_TO_EVENT[trigger]


def default_severity(trigger: str, s: SignalSummary) -> str:
    """Severity rules from labeling_guide.md section 4 (the approved spec).

    Model A reports only phone_use (it can't tell texting from a call), so the
    offline fallback uses medium; tuned Gemini sets high when the keyframe shows texting.
    """
    perclos_high = s.perclos > PERCLOS_HIGH and not s.sunglasses
    if trigger == "eye_closure_long":
        sev = "high"                                   # any closure >= 1.0 s
    elif trigger == "nod":
        sev = "high" if perclos_high else "medium"     # nod alone -> medium
    elif trigger == "perclos_high":
        sev = "high" if s.nods_5min >= 1 else "medium"  # PERCLOS + nod -> high
    elif trigger in ("yawn", "face_touch"):
        sev = "medium" if (s.yawns_5min >= 3 or perclos_high) else "low"
    elif trigger == "phone_use":
        sev = "medium"
    elif trigger == "gaze_away":
        sev = "medium" if s.gaze_away_s > GAZE_MEDIUM_S else "low"
    else:                                              # drinking, smoking
        sev = "low"
    # Sunglasses: eyes can't be measured reliably -> eye-based drowsy capped at medium.
    if s.sunglasses and trigger in ("eye_closure_long", "perclos_high") and sev == "high":
        sev = "medium"
    return sev


def template_verdict(req: VerifyRequest) -> GeminiVerdict:
    s = req.signals
    event = fallback_event(req.trigger, s)
    sev = default_severity(req.trigger, s)
    if event == "drowsy" and req.trigger == "nod":
        msg, action = NOD_MESSAGES[sev if sev in NOD_MESSAGES else "medium"]
    elif event == "drowsy" and s.sunglasses:
        msg, action = ("You seem tired, so plan a break at the next safe stop.", "take_break_soon")
    else:
        msg, action = TEMPLATES.get((event, sev)) or TEMPLATES[(event, "low" if (event, "low") in TEMPLATES else "medium")]
    return GeminiVerdict(event=event, confirmed="yes", severity=sev, message=msg, action=action)


def apply_authority_rule(req: VerifyRequest, v: GeminiVerdict) -> tuple[GeminiVerdict, bool]:
    """Return (verdict, overridden).

    Measured triggers (eye closure >= 1.0 s, PERCLOS, nod) are never dismissed:
    a false_alarm / confirmed=no / non-fatigue answer is replaced by the template.
    A long eye closure without sunglasses is always high / pull_over_now;
    with sunglasses, eye-based severity is capped at medium.
    """
    if req.trigger not in MEASURED_TRIGGERS:
        return v, False
    if v.event == "false_alarm" or v.confirmed == "no" or v.event not in ("drowsy", "early_fatigue"):
        return template_verdict(req), True
    s = req.signals
    eye_trigger = req.trigger in ("eye_closure_long", "perclos_high")
    if req.trigger == "eye_closure_long" and not s.sunglasses and v.severity != "high":
        return v.model_copy(update={"event": "drowsy", "severity": "high", "action": "pull_over_now"}), True
    if s.sunglasses and eye_trigger and v.severity == "high":
        return v.model_copy(update={"severity": "medium", "action": "take_break_soon"}), True
    return v, False


@dataclass
class Cooldown:
    seconds: float = 30.0
    _last: dict[tuple[str, str], float] = field(default_factory=dict)

    def allow(self, trip_id: str, trigger: str, now: float | None = None) -> bool:
        now = time.monotonic() if now is None else now
        key = (trip_id, trigger)
        if now - self._last.get(key, -1e9) < self.seconds:
            return False
        self._last[key] = now
        return True
