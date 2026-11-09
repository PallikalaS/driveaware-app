"""Request/response schema for the DriveAware backend.

Label words are copied verbatim from driveaware-models/driveaware_data/labels.py
(the To-Dos doc "Shared rules and formats"). Do not rename them.
"""
from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator

Trigger = Literal["eye_closure_long", "perclos_high", "yawn", "nod", "gaze_away",
                  "phone_use", "drinking", "smoking", "face_touch"]
GeminiEvent = Literal["drowsy", "distracted_phone", "distracted_gaze", "drinking",
                      "smoking", "early_fatigue", "false_alarm"]
Severity = Literal["low", "medium", "high"]
Action = Literal["none", "refocus", "take_break_soon", "pull_over_now"]
AppClass = Literal["safe_driving", "phone_use", "drinking", "smoking", "face_touch"]

# Triggers measured directly from face landmarks. Authority rule: Gemini may
# never call these a false_alarm (labeling_guide.md).
MEASURED_TRIGGERS = {"eye_closure_long", "perclos_high", "nod"}
MESSAGE_MAX_WORDS = 20


class SignalSummary(BaseModel):
    """Last 10 s of on-device signals that accompany the keyframe."""
    window_s: float = 10.0
    perclos: float = Field(ge=0, le=1)
    blinks: int = 0
    longest_closure_s: float = 0.0
    yawns_5min: int = 0
    nods_5min: int = 0
    gaze_away_s: float = 0.0
    yaw_deg: float = 0.0
    pitch_deg: float = 0.0
    model_a_class: Optional[AppClass] = None
    model_a_conf: Optional[float] = Field(default=None, ge=0, le=1)
    hand_occupied: bool = False
    sunglasses: bool = False
    fatigue_score: Optional[float] = None


class VerifyRequest(BaseModel):
    trip_id: str
    t_s: float
    trigger: Trigger
    level: int = Field(ge=1, le=3)
    signals: SignalSummary
    keyframe_jpeg_b64: Optional[str] = None


class GeminiVerdict(BaseModel):
    """Exactly what the tuned Gemini model must return (response_schema)."""
    event: GeminiEvent
    confirmed: Literal["yes", "no"]
    severity: Severity
    message: str
    action: Action

    @field_validator("message")
    @classmethod
    def short_message(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("empty message")
        if len(v.split()) > MESSAGE_MAX_WORDS:
            raise ValueError(f"message longer than {MESSAGE_MAX_WORDS} words")
        return v


class VerifyResponse(GeminiVerdict):
    source: Literal["gemini", "authority_override", "template", "cooldown"]
    latency_ms: int


class TripEvent(BaseModel):
    t_s: float
    trigger: Trigger
    level: int
    event: Optional[GeminiEvent] = None
    severity: Optional[Severity] = None


class SummaryRequest(BaseModel):
    trip_id: str
    duration_s: float
    events: list[TripEvent]


class SummaryResponse(BaseModel):
    trip_id: str
    duration_min: float
    alerts: int
    by_event: dict[str, int]
    highest_severity: Optional[Severity]
    advice: str


# JSON schema handed to Gemini as response_schema (OpenAPI subset).
GEMINI_RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "event": {"type": "STRING", "enum": list(GeminiEvent.__args__)},
        "confirmed": {"type": "STRING", "enum": ["yes", "no"]},
        "severity": {"type": "STRING", "enum": list(Severity.__args__)},
        "message": {"type": "STRING"},
        "action": {"type": "STRING", "enum": list(Action.__args__)},
    },
    "required": ["event", "confirmed", "severity", "message", "action"],
    "propertyOrdering": ["event", "confirmed", "severity", "message", "action"],
}
