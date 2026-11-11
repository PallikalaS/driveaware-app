"""The offline fallback must agree with labeling_guide.md (driveaware-models).

Each case is one of Bindu's worked examples (section 6) or a rule from
section 4. If the guide changes, change these cases first.
"""
import pytest

from app.logic import apply_authority_rule, template_verdict
from app.schema import GeminiVerdict, SignalSummary, VerifyRequest


def req(trigger, level=2, **sig):
    sig.setdefault("perclos", 0.05)
    return VerifyRequest(trip_id="t", t_s=1, trigger=trigger, level=level, signals=SignalSummary(**sig))


@pytest.mark.parametrize("name, r, event, severity, action", [
    ("ex1 closure 1.6 s", req("eye_closure_long", longest_closure_s=1.6, perclos=0.12), "drowsy", "high", "pull_over_now"),
    ("closure 1.2 s is already high", req("eye_closure_long", longest_closure_s=1.2), "drowsy", "high", "pull_over_now"),
    ("ex2 PERCLOS + 3 yawns", req("perclos_high", perclos=0.21, yawns_5min=3), "drowsy", "medium", "take_break_soon"),
    ("PERCLOS + nod", req("perclos_high", perclos=0.21, nods_5min=1), "drowsy", "high", "pull_over_now"),
    ("ex3 nod alone", req("nod", nods_5min=1, perclos=0.09), "drowsy", "medium", "take_break_soon"),
    ("nod while PERCLOS high", req("nod", nods_5min=1, perclos=0.2), "drowsy", "high", "pull_over_now"),
    ("ex4 one yawn", req("yawn", yawns_5min=1, perclos=0.06), "early_fatigue", "low", "take_break_soon"),
    ("ex5 3 yawns, sunglasses", req("yawn", yawns_5min=3, perclos=0.10, sunglasses=True), "early_fatigue", "medium", "take_break_soon"),
    ("yawn while PERCLOS high", req("yawn", yawns_5min=1, perclos=0.2), "drowsy", "medium", "take_break_soon"),
    ("ex11 PERCLOS under sunglasses", req("perclos_high", perclos=0.24, yawns_5min=1, sunglasses=True), "drowsy", "medium", "take_break_soon"),
    ("closure under sunglasses capped", req("eye_closure_long", longest_closure_s=1.6, sunglasses=True), "drowsy", "medium", "take_break_soon"),
    ("ex12 face touch", req("face_touch", perclos=0.07), "early_fatigue", "low", "take_break_soon"),
    ("phone (Model A can't tell texting)", req("phone_use"), "distracted_phone", "medium", "refocus"),
    ("gaze 3 s", req("gaze_away", gaze_away_s=3.0), "distracted_gaze", "low", "refocus"),
    ("ex8 gaze 5 s", req("gaze_away", gaze_away_s=5.0), "distracted_gaze", "medium", "refocus"),
    ("drinking", req("drinking"), "drinking", "low", "refocus"),
    ("smoking", req("smoking"), "smoking", "low", "refocus"),
])
def test_fallback_matches_guide(name, r, event, severity, action):
    v = template_verdict(r)
    assert (v.event, v.severity, v.action) == (event, severity, action), name
    assert v.confirmed == "yes"
    assert v.message.endswith(".") and len(v.message.split()) <= 20


def test_sunglasses_messages_do_not_mention_eyes():
    for t in ("eye_closure_long", "perclos_high"):
        assert "eye" not in template_verdict(req(t, perclos=0.2, sunglasses=True)).message.lower()


def test_authority_rule_raises_1_2s_closure_to_high():
    v = GeminiVerdict(event="drowsy", confirmed="yes", severity="medium", message="Take a break soon.",
                      action="take_break_soon")
    out, changed = apply_authority_rule(req("eye_closure_long", longest_closure_s=1.2), v)
    assert changed and out.severity == "high" and out.action == "pull_over_now"


def test_authority_rule_caps_high_under_sunglasses():
    v = GeminiVerdict(event="drowsy", confirmed="yes", severity="high", message="Please pull over.",
                      action="pull_over_now")
    out, changed = apply_authority_rule(req("perclos_high", perclos=0.3, sunglasses=True), v)
    assert changed and out.severity == "medium" and out.action == "take_break_soon"


def test_false_alarm_still_allowed_for_mirror_check():
    v = GeminiVerdict(event="false_alarm", confirmed="no", severity="low", message="No alert needed.", action="none")
    out, changed = apply_authority_rule(req("gaze_away", gaze_away_s=2.3), v)
    assert not changed and out.event == "false_alarm"
