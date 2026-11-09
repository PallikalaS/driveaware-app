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

TEMPLATES = {
    # (event, severity) -> (message, action)
    ("drowsy", "high"): ("You seem very drowsy. Please pull over safely and rest.", "pull_over_now"),
    ("drowsy", "medium"): ("Signs of drowsiness. Plan a break at the next safe stop.", "take_break_soon"),
    ("drowsy", "low"): ("You look a little tired. Stay alert and consider a break soon.", "take_break_soon"),
    ("early_fatigue", "low"): ("Early signs of fatigue. Consider a short break soon.", "take_break_soon"),
    ("early_fatigue", "medium"): ("Fatigue is building. Please take a break soon.", "take_break_soon"),
    ("distracted_phone", "medium"): ("Please put the phone down and watch the road.", "refocus"),
    ("distracted_gaze", "low"): ("Eyes back on the road, please.", "refocus"),
    ("drinking", "low"): ("Keep your eyes on the road while drinking.", "refocus"),
    ("smoking", "low"): ("Keep both eyes on the road, please.", "refocus"),
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


def default_severity(trigger: str, s: SignalSummary) -> str:
    """Numeric severity rules (mirror labeling_guide.md)."""
    if trigger == "eye_closure_long":
        return "high" if s.longest_closure_s >= 1.5 else "medium"
    if trigger == "perclos_high":
        return "medium" if s.yawns_5min >= 3 or s.nods_5min >= 1 else "low"
    if trigger == "nod":
        return "high"
    if trigger == "phone_use":
        return "medium"
    if trigger == "yawn":
        return "medium" if s.yawns_5min >= 3 else "low"
    return "low"


def template_verdict(req: VerifyRequest) -> GeminiVerdict:
    event = TRIGGER_TO_EVENT[req.trigger]
    sev = default_severity(req.trigger, req.signals)
    msg, action = TEMPLATES.get((event, sev)) or TEMPLATES.get((event, "low")) or \
        ("Please stay focused on the road.", "refocus")
    return GeminiVerdict(event=event, confirmed="yes", severity=sev, message=msg, action=action)


def apply_authority_rule(req: VerifyRequest, v: GeminiVerdict) -> tuple[GeminiVerdict, bool]:
    """Return (verdict, overridden). Measured triggers are always confirmed drowsy."""
    if req.trigger not in MEASURED_TRIGGERS:
        return v, False
    if v.event == "false_alarm" or v.confirmed == "no" or v.event not in ("drowsy", "early_fatigue"):
        return template_verdict(req), True
    if req.trigger == "eye_closure_long" and req.signals.longest_closure_s >= 1.5 and v.severity != "high":
        return v.model_copy(update={"severity": "high", "action": "pull_over_now"}), True
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
