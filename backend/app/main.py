"""DriveAware Cloud Run backend.

POST /verify   keyframe + 10 s signal summary  -> tuned-Gemini verdict (JSON)
POST /summary  trip events                      -> trip summary
GET  /health    (not /healthz: Cloud Run reserves paths ending in "z")

The phone never holds a key: Cloud Run runs as a least-privilege service
account with roles/aiplatform.user; the app calls this URL.

Env:
  GCP_PROJECT
  VERTEX_LOCATION  Vertex AI location for Gemini (default "us": the multi-region
                   endpoint where Gemini 3.5 Flash tuning and tuned serving run)
  GEMINI_MODEL   base model id (default gemini-3.5-flash) or, after tuning, the
                 tuned endpoint resource name, e.g.
                 projects/<p>/locations/us/endpoints/<id>
                 (gemini-2.5-flash was retired on Vertex AI on Oct 20, 2026)
                 (before tuning finishes: the base model id you confirmed)
  SUMMARY_MODEL  model for the end-of-trip note (default gemini-3.5-flash: always the base
                 model, the tuned one only knows the verify JSON task)
  GEMINI_TIMEOUT_S (default 4), SUMMARY_TIMEOUT_S (default 8), COOLDOWN_S (default 30)
  APP_TOKEN      optional shared token checked in X-App-Token header
"""
from __future__ import annotations

import base64
import json
import logging
import os
import time
from collections import Counter

from fastapi import FastAPI, Header, HTTPException

from .logic import (SUMMARY_INSTRUCTION, SYSTEM_INSTRUCTION, Cooldown, apply_authority_rule, build_prompt,
                    rule_advice, summary_prompt, template_verdict)
from .schema import (GEMINI_RESPONSE_SCHEMA, GeminiVerdict, SummaryRequest, SummaryResponse,
                     VerifyRequest, VerifyResponse)

log = logging.getLogger("driveaware")
app = FastAPI(title="DriveAware backend", version="0.1.0")
cooldown = Cooldown(float(os.getenv("COOLDOWN_S", "30")))
_client = None


def gemini():
    global _client
    if _client is None:
        from google import genai
        _client = genai.Client(vertexai=True, project=os.environ["GCP_PROJECT"],
                               location=os.getenv("VERTEX_LOCATION", "us"))
    return _client


def call_gemini(req: VerifyRequest) -> GeminiVerdict:
    from google.genai import types
    parts = []
    if req.keyframe_jpeg_b64:
        parts.append(types.Part.from_bytes(data=base64.b64decode(req.keyframe_jpeg_b64),
                                           mime_type="image/jpeg"))
    parts.append(types.Part.from_text(text=build_prompt(req.trigger, req.level, req.signals)))
    resp = gemini().models.generate_content(
        model=os.getenv("GEMINI_MODEL", "gemini-3.5-flash"),
        contents=[types.Content(role="user", parts=parts)],
        config=types.GenerateContentConfig(
            system_instruction=SYSTEM_INSTRUCTION,
            # Gemini 3: keep the default temperature; minimal thinking for the
            # tuned, short-answer task (Google's tuning guidance).
            thinking_config=types.ThinkingConfig(thinking_level=types.ThinkingLevel.MINIMAL),
            max_output_tokens=512,
            response_mime_type="application/json",
            response_schema=GEMINI_RESPONSE_SCHEMA,
            http_options=types.HttpOptions(timeout=int(float(os.getenv("GEMINI_TIMEOUT_S", "4")) * 1000)),
        ),
    )
    return GeminiVerdict.model_validate(json.loads(resp.text))


def check_token(token: str | None):
    want = os.getenv("APP_TOKEN")
    if want and token != want:
        raise HTTPException(status_code=401, detail="bad token")


@app.get("/health")
def health():
    return {"ok": True, "model": os.getenv("GEMINI_MODEL", "gemini-3.5-flash"),
            "location": os.getenv("VERTEX_LOCATION", "us")}


@app.post("/verify", response_model=VerifyResponse)
def verify(req: VerifyRequest, x_app_token: str | None = Header(default=None)):
    check_token(x_app_token)
    t0 = time.perf_counter()
    if not cooldown.allow(req.trip_id, req.trigger):
        v, source = template_verdict(req), "cooldown"
    else:
        try:
            v = call_gemini(req)
            v, overridden = apply_authority_rule(req, v)
            source = "authority_override" if overridden else "gemini"
        except Exception as e:  # timeout, invalid JSON, quota … -> safe template
            log.warning("gemini failed: %s", e)
            v, source = template_verdict(req), "template"
    ms = int((time.perf_counter() - t0) * 1000)
    return VerifyResponse(**v.model_dump(), source=source, latency_ms=ms)


@app.post("/summary", response_model=SummaryResponse)
def summary(req: SummaryRequest, x_app_token: str | None = Header(default=None)):
    check_token(x_app_token)
    rank = {"low": 1, "medium": 2, "high": 3}
    by_event = Counter(e.event or e.trigger for e in req.events if e.event != "false_alarm")
    by_level = Counter(e.level for e in req.events if e.event != "false_alarm")
    dismissed = sum(e.event == "false_alarm" for e in req.events)
    sev = max((e.severity for e in req.events if e.severity), key=lambda s: rank[s], default=None)
    mins = req.duration_s / 60
    alerts = sum(by_level.values())
    advice = rule_advice(dict(by_event), sev, alerts)
    note, source = advice, "template"
    try:
        from google.genai import types
        resp = gemini().models.generate_content(
            model=os.getenv("SUMMARY_MODEL", "gemini-3.5-flash"),
            contents=summary_prompt(mins, dict(by_event), sev, dict(by_level), req.peak_fatigue, dismissed),
            config=types.GenerateContentConfig(
                system_instruction=SUMMARY_INSTRUCTION,
                thinking_config=types.ThinkingConfig(thinking_level=types.ThinkingLevel.MINIMAL),
                max_output_tokens=256,
                http_options=types.HttpOptions(timeout=int(float(os.getenv("SUMMARY_TIMEOUT_S", "8")) * 1000)),
            ),
        )
        if resp.text and resp.text.strip():
            note, source = resp.text.strip(), "gemini"
    except Exception as e:
        log.warning("summary gemini failed: %s", e)
    return SummaryResponse(trip_id=req.trip_id, duration_min=round(mins, 1), alerts=alerts,
                           by_event=dict(by_event), highest_severity=sev, advice=advice,
                           summary=note, summary_source=source)
