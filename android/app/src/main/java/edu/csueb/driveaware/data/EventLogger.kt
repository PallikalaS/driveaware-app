package edu.csueb.driveaware.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event log in exactly the format eval/match_events.py reads:
 *   t_s,event_type,level,source,mode,model_a_class,model_a_conf,
 *   gemini_event,gemini_confirmed,severity,latency_ms,fps
 * t_s = seconds since monitoring started. One file per session.
 */
class EventLogger(context: Context) {
    data class Row(
        val tS: Float, val eventType: String, val level: Int, val source: String, val mode: String,
        val modelAClass: String? = null, val modelAConf: Float? = null,
        val geminiEvent: String? = null, val geminiConfirmed: String? = null,
        val severity: String? = null, val latencyMs: Int? = null, val fps: Float? = null,
    )

    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    var file: File? = null; private set
    private val rows = mutableListOf<Row>()

    fun start(sessionId: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "${sessionId}_log_$stamp.csv").apply { writeText(HEADER + "\n") }
        rows.clear()
    }

    fun log(r: Row) {
        rows += r
        file?.appendText(listOf(
            "%.2f".format(Locale.US, r.tS), r.eventType, r.level, r.source, r.mode,
            r.modelAClass ?: "", r.modelAConf?.let { "%.2f".format(Locale.US, it) } ?: "",
            r.geminiEvent ?: "", r.geminiConfirmed ?: "", r.severity ?: "",
            r.latencyMs ?: "", r.fps?.let { "%.1f".format(Locale.US, it) } ?: "",
        ).joinToString(",") + "\n")
    }

    fun rows(): List<Row> = rows.toList()

    companion object {
        const val HEADER = "t_s,event_type,level,source,mode,model_a_class,model_a_conf," +
            "gemini_event,gemini_confirmed,severity,latency_ms,fps"
    }
}
