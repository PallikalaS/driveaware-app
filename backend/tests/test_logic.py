import pytest
from pydantic import ValidationError

from app.logic import Cooldown, apply_authority_rule, build_prompt, template_verdict
from app.schema import GeminiVerdict, SignalSummary, VerifyRequest


def req(trigger="eye_closure_long", closure=2.0, **kw):
    return VerifyRequest(trip_id="t1", t_s=10, trigger=trigger, level=3,
                         signals=SignalSummary(perclos=0.3, longest_closure_s=closure, **kw))


def test_authority_rule_overrides_false_alarm_on_measured_signal():
    v = GeminiVerdict(event="false_alarm", confirmed="no", severity="low",
                      message="Looks fine.", action="none")
    out, overridden = apply_authority_rule(req(), v)
    assert overridden and out.event == "drowsy" and out.confirmed == "yes"
    assert out.severity == "high" and out.action == "pull_over_now"


def test_false_alarm_allowed_for_visual_events():
    v = GeminiVerdict(event="false_alarm", confirmed="no", severity="low",
                      message="Just a mirror check.", action="none")
    out, overridden = apply_authority_rule(req("gaze_away"), v)
    assert not overridden and out.event == "false_alarm"


def test_message_word_limit():
    with pytest.raises(ValidationError):
        GeminiVerdict(event="drowsy", confirmed="yes", severity="low",
                      message=" ".join(["word"] * 21), action="none")


def test_templates_are_valid_for_every_trigger():
    for t in ["eye_closure_long", "perclos_high", "yawn", "nod", "gaze_away",
              "phone_use", "drinking", "smoking", "face_touch"]:
        v = template_verdict(req(t))
        assert len(v.message.split()) <= 20


def test_cooldown():
    c = Cooldown(30)
    assert c.allow("t", "yawn", now=0)
    assert not c.allow("t", "yawn", now=10)
    assert c.allow("t", "nod", now=10)
    assert c.allow("t", "yawn", now=31)


def test_prompt_is_stable():
    p = build_prompt("yawn", 1, SignalSummary(perclos=0.12, yawns_5min=2))
    assert p.startswith("Signals (last 10 s): trigger=yawn level=1 perclos=0.12")


def test_override_message_matches_corrected_severity():
    # Seen on Cloud Run: Gemini said medium "take a short break"; override raised it
    # to high/pull_over_now but kept the soft message. Message must match the action.
    from app.logic import apply_authority_rule, TEMPLATES
    from app.schema import GeminiVerdict, SignalSummary, VerifyRequest
    req = VerifyRequest(trip_id="t", t_s=42, trigger="eye_closure_long", level=3,
                        signals=SignalSummary(perclos=0.22, longest_closure_s=1.4))
    g = GeminiVerdict(event="drowsy", confirmed="yes", severity="medium",
                      message="You seem tired. Please take a short break to rest.", action="take_break_soon")
    v, overridden = apply_authority_rule(req, g)
    assert overridden and v.severity == "high" and v.action == "pull_over_now"
    assert v.message == TEMPLATES[("drowsy", "high")][0]
