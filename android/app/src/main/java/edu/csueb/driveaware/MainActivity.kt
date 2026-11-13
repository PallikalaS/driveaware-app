package edu.csueb.driveaware

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.csueb.driveaware.ui.Mode
import edu.csueb.driveaware.ui.MonitorViewModel
import edu.csueb.driveaware.ui.Screen
import edu.csueb.driveaware.ui.UiState
import edu.csueb.driveaware.signals.ActivityRules
import edu.csueb.driveaware.signals.FatigueScore
import edu.csueb.driveaware.vision.FaceLandmarkerHelper
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()
    private var landmarker: FaceLandmarkerHelper? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var hasCamera by mutableStateOf(false)
    private var cameraAsked = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    /** Recovery (#34): ask again; if Android won't show the dialog any more, open the app's settings. */
    private fun requestCamera() {
        if (cameraAsked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else { cameraAsked = true; askCamera.launch(Manifest.permission.CAMERA) }
    }

    override fun onResume() {
        super.onResume()   // back from the settings screen with permission granted
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) requestCamera()
        landmarker = FaceLandmarkerHelper(
            this,
            onFrame = { f, r, bmp -> vm.onFrame(f, bmp, r?.faceLandmarks()?.firstOrNull() ?: emptyList()) },
            onError = { msg -> vm.onModelError(msg) },
        ).also { vm.setDelegate(it.delegateName) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (hasCamera) CameraPreview()
                            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("DriveAware needs the front camera to watch for drowsiness.")
                                Button(onClick = { requestCamera() }) { Text("Allow camera") }
                            }
                            if (ui.showDebug) DebugOverlay(ui)
                            StatusChips(ui)
                            AlertBanner(ui)
                        }
                        Column(Modifier.width(320.dp).fillMaxHeight().padding(12.dp)
                            .verticalScroll(rememberScrollState())) {
                            when (ui.screen) {
                                Screen.HOME -> Home(ui)
                                Screen.CALIBRATION -> CalibrationPanel(ui)
                                Screen.MONITORING -> MonitoringPanel(ui)
                                Screen.SETTINGS -> SettingsPanel()
                                Screen.SUMMARY -> SummaryPanel(ui)
                                Screen.TEST -> TestPanel(ui)
                            }
                        }
                    }
                    FullScreenFlash(ui)
                } }
            }
        }
    }

    @Composable private fun CameraPreview() {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).also { pv ->
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().also { it.setAnalyzer(analyzerExecutor) { img -> landmarker?.detect(img) } }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxSize())
    }

    @Composable private fun DebugOverlay(ui: UiState) {
        val e = ui.engine; val r = ui.raw
        Text(
            buildString {
                appendLine("fps %.1f  face %s  %s  %s".format(ui.fps, if (r?.faceFound == true) "yes" else "NO", ui.mode.csv, ui.delegate))
                ui.modelError?.let { appendLine("⚠ $it") }
                appendLine("EAR %.3f  blink %.2f  closed %s %.1fs".format(r?.ear ?: 0f, r?.eyeBlink ?: 0f, e.eyesClosed, e.closureS))
                appendLine("PERCLOS %.2f  blinks/10s %d".format(e.perclos, e.blinks))
                appendLine("MAR %.2f  jaw %.2f  yawns5m %d".format(r?.mar ?: 0f, r?.jawOpen ?: 0f, e.yawns5min))
                appendLine("yawΔ %.0f  pitchΔ %.0f  nods5m %d  gaze %.1fs".format(e.yawDelta, e.pitchDelta, e.nods5min, e.gazeAwayS))
                appendLine("eyes b%.0f s%.0f  eye/cheek %.2f  sunglasses %s  fatigue %.0f".format(r?.eyeBrightness ?: -1f, r?.eyeSharpness ?: -1f,
                    if ((r?.cheekBrightness ?: -1f) > 10f) r!!.eyeBrightness / r.cheekBrightness else -1f,
                    if (ui.sunglasses) "YES" else "no", ui.fatigue))
                appendLine("ModelA %s  [%s]".format(ui.modelA?.let { "%s %.2f".format(it.appClass, it.conf) }
                    ?: if (ui.mode == Mode.RULES_ONLY) "off (rules only)" else "none",
                    when { ui.forcedModelA != null -> "forced"; ui.modelALoaded -> "model_a_v1"; else -> "no model" }))
                ui.modelAError?.let { appendLine("⚠ $it") }
            },
            Modifier.padding(8.dp).background(Color(0x99000000)).padding(6.dp),
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
    }

    @Composable private fun BoxScope.AlertBanner(ui: UiState) {
        if (ui.screen != Screen.MONITORING) return   // don't leave the last trip's alert on other screens
        val a = ui.alert ?: return
        // proposal section 9: amber = fatigue, blue = distraction, red = critical
        val bg = when {
            a.level >= 3 -> Color(0xEECC0000)
            a.kind == "distraction" -> Color(0xEE1565C0)
            else -> Color(0xEEE08000)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg).padding(14.dp)) {
            Text("Level ${a.level}: ${a.message}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Text("  Gemini is checking…", color = Color.White, fontSize = 13.sp)
                } else if (a.source == "gemini") Text("Checked by Gemini", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.acknowledgeAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Text("I'M OK", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    /** Level 3: full-screen red flash (proposal section 3), until the driver taps I'M OK. */
    @Composable private fun FullScreenFlash(ui: UiState) {
        val a = ui.alert
        if (ui.screen != Screen.MONITORING || a == null || a.level < 3) return
        val pulse by rememberInfiniteTransition(label = "l3").animateFloat(
            0.0f, 0.45f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "l3a")
        Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = pulse)))
    }

    /** Recovery states (#34), shown whenever they apply, with or without an alert. */
    @Composable private fun BoxScope.StatusChips(ui: UiState) {
        if (ui.screen != Screen.MONITORING) return
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
            @Composable fun chip(text: String, color: Color) = Text(text, Modifier.padding(2.dp)
                .background(Color(0xCC000000)).padding(6.dp), color = color, fontWeight = FontWeight.Bold)
            if (ui.faceLostS >= 3f) chip("Face not found: adjust the phone", Color(0xFFFF6E6E))
            if (ui.sunglasses) chip("Eye tracking limited: sunglasses", Color(0xFFFFD54F))
            if (!ui.online && ui.mode == Mode.FULL) chip("Offline: local alerts only", Color.Yellow)
        }
    }

    @Composable private fun Home(ui: UiState) {
        Text("DriveAware", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        // readiness chips (proposal screen 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Ready("Camera", hasCamera)
            Ready("Calibrated", ui.calibrated)
            Ready("Cloud", edu.csueb.driveaware.BuildConfig.BACKEND_URL.isNotBlank())
            Ready("Model A", ui.modelALoaded)
        }
        if (!ui.calibrated) Text("Please calibrate first", color = Color.Yellow, fontSize = 13.sp)
        ui.lastTrip?.let { Text("Last trip: $it", fontSize = 12.sp, color = Color.LightGray) }
        Spacer(Modifier.height(8.dp))
        // Session ID for test/validation runs (S01…S08, V01…V03). Blank = normal trip.
        var sessionId by remember { mutableStateOf("") }
        OutlinedTextField(
            value = sessionId,
            onValueChange = { v -> sessionId = v.uppercase().filter { it.isLetterOrDigit() }.take(8) },
            label = { Text("Session ID (e.g. S01) — optional") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.startMonitoring(sessionId.ifBlank { "trip" }) },
            enabled = ui.calibrated, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (sessionId.isBlank()) "Start trip" else "Start $sessionId") }
        OutlinedButton(onClick = { vm.startCalibration() }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate (10 s)") }
        OutlinedButton(onClick = { vm.go(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
        OutlinedButton(onClick = { vm.go(Screen.TEST) }, modifier = Modifier.fillMaxWidth()) { Text("Test options") }
        // Show non-default test settings on Home so nobody runs a trip with them by mistake.
        if (ui.mode != Mode.FULL) Text("Test mode: ${ui.mode.csv}", color = Color.Yellow, fontSize = 13.sp)
        ui.forcedModelA?.let { Text("Model A forced to $it", color = Color.Yellow, fontSize = 13.sp) }
    }

    @Composable private fun Ready(label: String, ok: Boolean) {
        Text((if (ok) "✓ " else "✗ ") + label, fontSize = 11.sp, color = if (ok) Color(0xFF81C784) else Color(0xFFE57373),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2A2E)).padding(horizontal = 6.dp, vertical = 2.dp))
    }

    @Composable private fun TestPanel(ui: UiState) {
        Row(verticalAlignment = Alignment.CenterVertically) {   // Done at the top: the list is long in landscape
            Text("Test options", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { vm.go(Screen.HOME) }) { Text("Done") }
        }
        Text("Test mode (ablation)")
        Mode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = ui.mode == m, onClick = { vm.setMode(m) }); Text(m.csv)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ui.showDebug, onCheckedChange = { vm.toggleDebug() }); Text("  Debug overlay")
        }
        Spacer(Modifier.height(8.dp))
        Text("Test: force Model A output")
        (listOf<String?>(null) + ActivityRules.APP_CLASSES).forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = ui.forcedModelA == c, onClick = { vm.forceModelA(c) }); Text(c ?: "off")
            }
        }
    }

    @Composable private fun CalibrationPanel(ui: UiState) {
        Text("Calibration", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Look at the road normally with your eyes open for 10 seconds.")
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { ui.calibrationProgress }, modifier = Modifier.fillMaxWidth())
        ui.calibrationError?.let {
            Text(it, color = Color.Yellow)
            Button(onClick = { vm.startCalibration() }) { Text("Try again") }
        }
        TextButton(onClick = { vm.go(Screen.HOME) }) { Text("Cancel") }
    }

    @Composable private fun MonitoringPanel(ui: UiState) {
        // Status badge (proposal section 9): pulsing dot, green / amber / blue / red
        val a = ui.alert
        val (badge, label) = when {
            a != null && a.level >= 3 -> Color(0xFFE53935) to "Critical alert"
            a != null && a.kind == "distraction" -> Color(0xFF42A5F5) to "Distraction"
            a != null || FatigueScore.band(ui.fatigue) != "alert" -> Color(0xFFFFA000) to "Fatigue"
            else -> Color(0xFF43A047) to "Monitoring"
        }
        val dot by rememberInfiniteTransition(label = "dot").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dota")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(badge.copy(alpha = dot)))
            Text("  $label", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = badge)
        }
        Text(when {
            ui.faceLostS >= 3f -> "Face not found: adjust the phone"
            ui.sunglasses -> "Eye tracking limited: sunglasses"
            else -> "Watching…"
        })
        if (ui.checking) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("  Gemini is checking an event…", fontSize = 13.sp)
        }
        // eyes / head / activity / glasses tiles (proposal screen 3)
        val e = ui.engine
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tile("Eyes", when { ui.sunglasses -> "hidden"; e.eyesClosed -> "closed"; else -> "open" },
                "PERCLOS %.0f%%".format(e.perclos * 100), Modifier.weight(1f))
            Tile("Head", when { e.gazeAwayS > 0f -> "away"; e.pitchDelta < -15f -> "down"; else -> "ok" },
                "nods ${e.nods5min}", Modifier.weight(1f))
            Tile("Activity", ui.modelA?.appClass?.replace("_", " ") ?: "–",
                ui.modelA?.let { "%.0f%%".format(it.conf * 100) } ?: "", Modifier.weight(1f))
            Tile("Glasses", if (ui.sunglasses) "sun" else "none", "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("Fatigue %.0f / 100: %s".format(ui.fatigue, FatigueScore.band(ui.fatigue)), fontSize = 14.sp)
        LinearProgressIndicator(progress = { ui.fatigue / 100f }, modifier = Modifier.fillMaxWidth(),
            color = when (FatigueScore.band(ui.fatigue)) { "drowsy" -> Color(0xFFE53935); "tiring" -> Color(0xFFFFA000); else -> Color(0xFF43A047) })
        ui.forcedModelA?.let { Text("Test: Model A forced to $it", color = Color.Yellow, fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.stopMonitoring() }, modifier = Modifier.fillMaxWidth()) { Text("End trip") }
    }

    @Composable private fun Tile(title: String, value: String, sub: String, modifier: Modifier) {
        Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2E)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 11.sp, color = Color.LightGray)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, color = Color.LightGray)
        }
    }

    @Composable private fun SettingsPanel() {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val th = remember { vm.settings.thresholds() }
        val current = remember { th.toCsvRows().toMap() }
        vm.settings.editable.forEach { (key, label, range) ->
            var v by remember { mutableFloatStateOf(current[key]?.toFloat() ?: range.first) }
            Text("$label: %.2f".format(v), fontSize = 13.sp)
            Slider(value = v, onValueChange = { v = it; vm.settings.setFloat(key, it) },
                valueRange = range.first..range.second)
        }
        Button(onClick = { vm.go(Screen.HOME) }) { Text("Done") }
    }

    private fun levelColor(level: Int) = when (level) { 3 -> Color(0xFFE53935); 2 -> Color(0xFFFFA000); else -> Color(0xFF42A5F5) }

    /** Fatigue over the trip with a tick for every alert, colored by level (proposal screen 5). */
    @Composable private fun FatigueChart(ui: UiState, alerts: List<edu.csueb.driveaware.data.EventLogger.Row>) {
        val pts = ui.fatigueTrace
        if (pts.size < 2) { Text("Trip too short for a chart", fontSize = 12.sp); return }
        val tMax = maxOf(pts.last().first, 1f)
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp).background(Color(0xFF1E1E22))) {
            val w = size.width; val h = size.height
            fun x(t: Float) = t / tMax * w
            fun y(f: Float) = h - f / 100f * h
            for (band in listOf(30f, 60f))   // tiring / drowsy lines
                drawLine(Color.Gray.copy(alpha = 0.5f), Offset(0f, y(band)), Offset(w, y(band)), 1f)
            alerts.forEach { a -> drawLine(levelColor(a.level), Offset(x(a.tS), 0f), Offset(x(a.tS), h), 3f) }
            val path = Path().apply {
                moveTo(x(pts[0].first), y(pts[0].second))
                pts.drop(1).forEach { (t, f) -> lineTo(x(t), y(f)) }
            }
            drawPath(path, Color(0xFF66BB6A), style = Stroke(width = 3f))
        }
        Text("Fatigue 0–100 over the trip; lines = alerts (blue L1, amber L2, red L3)", fontSize = 10.sp, color = Color.LightGray)
    }

    @Composable private fun SummaryPanel(ui: UiState) {
        Text("Trip summary", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val detected = ui.events.filter { it.source == "rules" || it.source == "model_a" }
        val verified = ui.events.filter { it.source.startsWith("gemini") }
        Text("Duration: %d min %02d s".format((ui.tripDurationS / 60).toInt(), (ui.tripDurationS % 60).toInt()))
        Text("Peak fatigue: %.0f / 100 (%s)".format(ui.peakFatigue, FatigueScore.band(ui.peakFatigue)))
        FatigueChart(ui, detected)
        Text("Alerts by level", fontWeight = FontWeight.Bold)
        for (lvl in 3 downTo 1) {
            val at = detected.filter { it.level == lvl }
            if (at.isEmpty()) continue
            Text("Level $lvl × ${at.size}: " + at.groupingBy { it.eventType }.eachCount()
                .entries.joinToString { "${it.key.replace('_', ' ')} ${it.value}" }, fontSize = 13.sp,
                color = levelColor(lvl))
        }
        if (detected.isEmpty()) Text("No alerts", fontSize = 13.sp)
        if (verified.isNotEmpty()) {
            val dismissed = verified.count { it.geminiEvent == "false_alarm" }
            Text("Checked by Gemini: ${verified.size} (dismissed as false alarm: $dismissed)", fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        when {
            ui.tripNoteLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("  Gemini is writing your summary…")
            }
            ui.tripNote != null -> {
                Text(ui.tripNote, fontWeight = FontWeight.Bold)
                Text(if (ui.tripNoteSource == "gemini") "Summary by Gemini" else "Offline summary",
                    fontSize = 11.sp, color = Color.LightGray)
            }
            else -> Text(when {   // offline: same rules as the backend fallback
                detected.any { it.level >= 3 } -> "Serious drowsiness was detected. Rest before your next drive."
                detected.isNotEmpty() -> "A few alerts this trip. Take regular breaks on longer drives."
                else -> "Clean trip with no alerts. Nice driving."
            }, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { ui.logPath?.let { shareLog(File(it)) } }) { Text("Export event log") }
        TextButton(onClick = { vm.go(Screen.HOME) }) { Text("Home") }
    }

    private fun shareLog(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export event log"))
    }

    override fun onDestroy() {
        super.onDestroy()
        landmarker?.close(); analyzerExecutor.shutdown()
    }
}
