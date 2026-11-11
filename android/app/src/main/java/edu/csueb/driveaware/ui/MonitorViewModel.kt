package edu.csueb.driveaware.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.csueb.driveaware.data.AlertManager
import edu.csueb.driveaware.data.BackendClient
import edu.csueb.driveaware.data.EventLogger
import edu.csueb.driveaware.data.SettingsStore
import edu.csueb.driveaware.signals.Calibration
import edu.csueb.driveaware.signals.DetectedEvent
import edu.csueb.driveaware.signals.EngineState
import edu.csueb.driveaware.signals.EventType
import edu.csueb.driveaware.signals.FrameSignals
import edu.csueb.driveaware.signals.TemporalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { HOME, CALIBRATION, MONITORING, SETTINGS, SUMMARY }
/** Ablation switch used in system tests (Vanessa). */
enum class Mode(val csv: String) { RULES_ONLY("rules"), RULES_MODEL_A("rules_model_a"), FULL("full") }

data class UiAlert(val level: Int, val message: String, val source: String, val tS: Float)

data class UiState(
    val screen: Screen = Screen.HOME,
    val engine: EngineState = EngineState(),
    val raw: FrameSignals? = null,
    val calibrationProgress: Float = 0f,
    val calibrationError: String? = null,
    val calibrated: Boolean = false,
    val alert: UiAlert? = null,
    val fps: Float = 0f,
    val mode: Mode = Mode.FULL,
    val online: Boolean = true,
    val showDebug: Boolean = true,
    val logPath: String? = null,
    val events: List<EventLogger.Row> = emptyList(),
    val tripStartMs: Long = 0,
    val modelError: String? = null,
    val delegate: String = "",
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    val settings = SettingsStore(app)
    private val engine = TemporalEngine(settings.thresholds(), settings.baseline() ?: edu.csueb.driveaware.signals.Baseline())
    private var calibration: Calibration? = null
    private val logger = EventLogger(app)
    private val alerts = AlertManager(app)
    private val backend = BackendClient()
    private var tripId = ""
    private var monitorStartMs = 0L
    private var lastFrameMs = 0L
    private var latestFrame: Bitmap? = null

    private val _ui = MutableStateFlow(UiState(calibrated = settings.baseline() != null))
    val ui: StateFlow<UiState> = _ui

    fun go(s: Screen) = _ui.update { it.copy(screen = s) }
    fun setMode(m: Mode) = _ui.update { it.copy(mode = m) }
    fun toggleDebug() = _ui.update { it.copy(showDebug = !it.showDebug) }

    fun startCalibration() {
        calibration = Calibration()
        _ui.update { it.copy(screen = Screen.CALIBRATION, calibrationProgress = 0f, calibrationError = null) }
    }

    /** [sessionId] names the event log: S01 → S01_log_<timestamp>.csv, which match_events.py pairs with S01.csv. */
    fun startMonitoring(sessionId: String = "trip") {
        engine.th = settings.thresholds()
        settings.baseline()?.let { engine.base = it }
        engine.reset()
        tripId = UUID.randomUUID().toString().take(8)
        logger.start(sessionId)
        monitorStartMs = 0
        _ui.update { it.copy(screen = Screen.MONITORING, alert = null, logPath = logger.file?.absolutePath,
            events = emptyList(), online = backend.configured) }
    }

    fun stopMonitoring() = _ui.update { it.copy(screen = Screen.SUMMARY, events = logger.rows()) }

    /** Called on the camera analyzer thread for every processed frame. */
    /** Face-model errors are shown on the debug overlay instead of failing silently. */
    fun onModelError(msg: String) = _ui.update { it.copy(modelError = msg) }

    fun setDelegate(name: String) = _ui.update { it.copy(delegate = name) }

    fun onFrame(f: FrameSignals, frame: Bitmap) {
        latestFrame = frame
        if (_ui.value.modelError != null) _ui.update { it.copy(modelError = null) }
        val dt = f.tMs - lastFrameMs
        lastFrameMs = f.tMs
        val fps = if (dt > 0) 0.9f * _ui.value.fps + 0.1f * (1000f / dt) else _ui.value.fps

        when (_ui.value.screen) {
            Screen.CALIBRATION -> calibration?.let { c ->
                c.add(f)
                if (c.done(f.tMs)) {
                    val b = c.result()
                    if (b == null) {
                        _ui.update { it.copy(calibrationError = "Face not found — adjust the phone mount and try again.") }
                        calibration = null
                    } else {
                        settings.saveBaseline(b); engine.base = b; calibration = null
                        _ui.update { it.copy(calibrated = true, screen = Screen.HOME) }
                    }
                }
                _ui.update { it.copy(calibrationProgress = c.progress(f.tMs), raw = f, fps = fps) }
            }
            Screen.MONITORING -> {
                if (monitorStartMs == 0L) monitorStartMs = f.tMs
                val rel = f.copy(tMs = f.tMs - monitorStartMs)
                val events = engine.process(rel)
                _ui.update { it.copy(engine = engine.state, raw = f, fps = fps) }
                events.forEach { handle(it, fps) }
            }
            else -> _ui.update { it.copy(raw = f, fps = fps) }
        }
    }

    private fun handle(e: DetectedEvent, fps: Float) {
        val mode = _ui.value.mode
        val local = localMessage(e.type, e.level)
        // Level 3 fires locally first; Gemini never delays it.
        if (e.level >= 3) alerts.fire(3, local)
        _ui.update { it.copy(alert = UiAlert(e.level, local, "local", e.tS)) }
        logger.log(EventLogger.Row(e.tS, e.type.csv, e.level, "rules", mode.csv, fps = fps))

        if (mode != Mode.FULL) { if (e.level < 3) alerts.fire(e.level, local); return }
        val state = engine.state
        val frame = latestFrame
        viewModelScope.launch(Dispatchers.IO) {
            val v = backend.verify(tripId, e.tS, e.type.csv, e.level, state, null, null, false, false, frame)
            engine.resetWindowStats()
            if (v == null) {
                _ui.update { it.copy(online = false) }
                if (e.level < 3) alerts.fire(e.level, local)
                return@launch
            }
            _ui.update { it.copy(online = true) }
            val dismissed = v.event == "false_alarm" && !e.type.measured
            logger.log(EventLogger.Row(e.tS, e.type.csv, e.level, "gemini:${v.source}", mode.csv,
                geminiEvent = v.event, geminiConfirmed = v.confirmed, severity = v.severity,
                latencyMs = v.latencyMs, fps = fps))
            if (dismissed) { _ui.update { it.copy(alert = null) }; return@launch }
            val lvl = maxOf(e.level, when (v.severity) { "high" -> 3; "medium" -> 2; else -> 1 })
            if (e.level >= 3) alerts.say(v.message) else alerts.fire(lvl, v.message)
            _ui.update { it.copy(alert = UiAlert(lvl, v.message, "gemini", e.tS)) }
        }
    }

    private fun localMessage(t: EventType, level: Int) = when (t) {
        EventType.EYE_CLOSURE_LONG, EventType.NOD ->
            if (level >= 3) "You seem very drowsy. Please pull over safely and rest." else "Your eyes closed for a while. Stay alert."
        EventType.PERCLOS_HIGH -> "Signs of drowsiness. Plan a break at the next safe stop."
        EventType.YAWN -> "Early signs of fatigue. Consider a short break soon."
        EventType.GAZE_AWAY -> "Eyes back on the road, please."
        EventType.PHONE_USE -> "Please put the phone down and watch the road."
        EventType.DRINKING, EventType.SMOKING, EventType.FACE_TOUCH -> "Keep your eyes on the road, please."
    }

    override fun onCleared() { alerts.close() }
}
