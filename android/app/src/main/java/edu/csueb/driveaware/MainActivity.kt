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
import androidx.compose.foundation.border
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
                Surface(Modifier.fillMaxSize()) {
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
                }
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
                appendLine("eyes b%.0f s%.0f  sunglasses %s  fatigue %.0f".format(r?.eyeBrightness ?: -1f, r?.eyeSharpness ?: -1f,
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
        val bg = when (a.level) { 3 -> Color(0xEECC0000); 2 -> Color(0xEEE08000); else -> Color(0xCC1565C0) }
        if (a.level >= 3) {   // #27: Level 3 also flashes a red frame around the whole camera view
            val pulse by rememberInfiniteTransition(label = "l3").animateFloat(
                0.15f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "l3a")
            Box(Modifier.matchParentSize().border(12.dp, Color.Red.copy(alpha = pulse)))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg).padding(16.dp)) {
            Text("Level ${a.level}: ${a.message}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }

    /** Recovery states (#34), shown whenever they apply, with or without an alert. */
    @Composable private fun BoxScope.StatusChips(ui: UiState) {
        if (ui.screen != Screen.MONITORING) return
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
            @Composable fun chip(text: String, color: Color) = Text(text, Modifier.padding(2.dp)
                .background(Color(0xCC000000)).padding(6.dp), color = color, fontWeight = FontWeight.Bold)
            if (ui.faceLostS >= 1f) chip("Face not found: check the phone mount", Color(0xFFFF6E6E))
            if (ui.sunglasses) chip("Eye tracking limited: sunglasses", Color(0xFFFFD54F))
            if (!ui.online && ui.mode == Mode.FULL) chip("Offline: local alerts only", Color.Yellow)
        }
    }

    @Composable private fun Home(ui: UiState) {
        Text("DriveAware", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(if (ui.calibrated) "Calibrated ✓" else "Please calibrate first", color = if (ui.calibrated) Color.Green else Color.Yellow)
        Spacer(Modifier.height(12.dp))
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
        Text("Monitoring", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(when {
            ui.faceLostS >= 1f -> "Face not found: check the mount"
            ui.sunglasses -> "Watching (eye tracking limited)…"
            else -> "Watching…"
        })
        Spacer(Modifier.height(8.dp))
        Text("Fatigue %.0f / 100: %s".format(ui.fatigue, FatigueScore.band(ui.fatigue)), fontSize = 14.sp)
        LinearProgressIndicator(progress = { ui.fatigue / 100f }, modifier = Modifier.fillMaxWidth(),
            color = when (FatigueScore.band(ui.fatigue)) { "drowsy" -> Color(0xFFE53935); "tiring" -> Color(0xFFFFA000); else -> Color(0xFF43A047) })
        ui.forcedModelA?.let { Text("Test: Model A forced to $it", color = Color.Yellow, fontSize = 12.sp) }
        ui.alert?.let { Text("Last alert @%.0fs: %s (%s)".format(it.tS, it.message, it.source), fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.stopMonitoring() }, modifier = Modifier.fillMaxWidth()) { Text("End trip") }
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

    @Composable private fun SummaryPanel(ui: UiState) {
        Text("Trip summary", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val detected = ui.events.filter { it.source == "rules" || it.source == "model_a" }
        val verified = ui.events.filter { it.source.startsWith("gemini") }
        Text("Duration: %d min %02d s".format((ui.tripDurationS / 60).toInt(), (ui.tripDurationS % 60).toInt()))
        Text("Peak fatigue: %.0f / 100 (%s)".format(ui.peakFatigue, FatigueScore.band(ui.peakFatigue)))
        Text("Alerts: ${detected.size}")
        detected.groupingBy { it.eventType }.eachCount().forEach { (k, n) -> Text("• $k × $n") }
        if (verified.isNotEmpty()) {
            val dismissed = verified.count { it.geminiEvent == "false_alarm" }
            Text("Checked by Gemini: ${verified.size} (dismissed as false alarm: $dismissed)", fontSize = 13.sp)
        }
        val advice = when {
            ui.peakFatigue > 60f || detected.any { it.level >= 3 } -> "Serious drowsiness was detected. Rest before your next drive."
            ui.peakFatigue >= 30f -> "You showed some signs of fatigue. Take breaks on long drives."
            else -> "No major fatigue signs on this trip."
        }
        Text(advice, fontWeight = FontWeight.Bold)
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
