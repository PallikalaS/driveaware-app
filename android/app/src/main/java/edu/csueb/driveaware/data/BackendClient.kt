package edu.csueb.driveaware.data

import android.graphics.Bitmap
import android.util.Base64
import edu.csueb.driveaware.BuildConfig
import edu.csueb.driveaware.signals.EngineState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * App → Cloud Run /verify. No keys on the phone; the backend's service account
 * calls Vertex AI. Any failure (offline, timeout, bad JSON) returns null and the
 * caller uses the local template message ("Offline: local alerts only").
 */
class BackendClient(private val baseUrl: String = BuildConfig.BACKEND_URL, private val timeoutMs: Int = 4000) {
    data class Verdict(
        val event: String, val confirmed: String, val severity: String,
        val message: String, val action: String, val source: String, val latencyMs: Int,
    )

    val configured get() = baseUrl.isNotBlank()

    /**
     * Cloud Run scales to zero, so the first call after idle takes 3–4 s (cold start)
     * and can hit the 4 s timeout. Called when a trip starts so the server is warm
     * before the first event. Returns true if the backend answered.
     */
    fun warmUp(): Boolean {
        if (!configured) return false
        return try {
            val c = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            c.responseCode == 200
        } catch (_: Exception) { false }
    }

    fun verify(
        tripId: String, tS: Float, trigger: String, level: Int, s: EngineState,
        modelAClass: String?, modelAConf: Float?, handOccupied: Boolean, sunglasses: Boolean,
        keyframe: Bitmap?, fatigueScore: Float? = null,
    ): Verdict? {
        if (!configured) return null
        val signals = JSONObject()
            .put("perclos", s.perclos.toDouble()).put("blinks", s.blinks)
            .put("longest_closure_s", s.longestClosureS.toDouble())
            .put("yawns_5min", s.yawns5min).put("nods_5min", s.nods5min)
            .put("gaze_away_s", s.gazeAwayS.toDouble())
            .put("yaw_deg", s.yawDelta.toDouble()).put("pitch_deg", s.pitchDelta.toDouble())
            .put("hand_occupied", handOccupied).put("sunglasses", sunglasses)
        fatigueScore?.let { signals.put("fatigue_score", it.toDouble()) }
        if (modelAClass != null) signals.put("model_a_class", modelAClass).put("model_a_conf", (modelAConf ?: 0f).toDouble())
        val body = JSONObject().put("trip_id", tripId).put("t_s", tS.toDouble())
            .put("trigger", trigger).put("level", level).put("signals", signals)
        keyframe?.let { body.put("keyframe_jpeg_b64", jpegB64(it)) }

        for (attempt in 0..1) {   // one retry
            try {
                val c = (URL("$baseUrl/verify").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = timeoutMs; readTimeout = timeoutMs
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode != 200) continue
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                return Verdict(j.getString("event"), j.getString("confirmed"), j.getString("severity"),
                    j.getString("message"), j.getString("action"), j.getString("source"), j.getInt("latency_ms"))
            } catch (_: Exception) { /* retry once, then offline */ }
        }
        return null
    }

    data class TripNote(val text: String, val source: String)

    /** End-of-trip note (Gemini-written on the backend, template if it can't reach Gemini). */
    fun summary(tripId: String, durationS: Float, rows: List<EventLogger.Row>, peakFatigue: Float): TripNote? {
        if (!configured) return null
        // one entry per detection, with Gemini's verdict when there was one
        val verdicts = rows.filter { it.source.startsWith("gemini") }.associateBy { it.tS to it.eventType }
        val events = org.json.JSONArray()
        rows.filter { it.source == "rules" || it.source == "model_a" }.forEach { r ->
            val v = verdicts[r.tS to r.eventType]
            events.put(JSONObject().put("t_s", r.tS.toDouble()).put("trigger", r.eventType).put("level", r.level)
                .apply { v?.geminiEvent?.let { put("event", it) }; v?.severity?.let { put("severity", it) } })
        }
        val body = JSONObject().put("trip_id", tripId).put("duration_s", durationS.toDouble())
            .put("events", events).put("peak_fatigue", peakFatigue.toDouble())
        return try {
            val c = (URL("$baseUrl/summary").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 12_000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            TripNote(j.getString("summary").ifBlank { j.getString("advice") }, j.optString("summary_source", "template"))
        } catch (_: Exception) { null }
    }

    private fun jpegB64(b: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(b, 512, 512 * b.height / b.width, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
