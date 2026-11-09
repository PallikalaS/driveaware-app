package edu.csueb.driveaware

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
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
import edu.csueb.driveaware.vision.FaceLandmarkerHelper
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()
    private var landmarker: FaceLandmarkerHelper? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var hasCamera by mutableStateOf(false)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA)
        landmarker = FaceLandmarkerHelper(this, onFrame = { f, _, bmp -> vm.onFrame(f, bmp) })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.ui.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (hasCamera) CameraPreview()
                            else Text("Camera permission needed", Modifier.align(Alignment.Center))
                            if (ui.showDebug) DebugOverlay(ui)
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
                appendLine("fps %.1f  face %s  %s".format(ui.fps, if (r?.faceFound == true) "yes" else "NO", ui.mode.csv))
                appendLine("EAR %.3f  closed %s %.1fs".format(r?.ear ?: 0f, e.eyesClosed, e.closureS))
                appendLine("PERCLOS %.2f  blinks/10s %d".format(e.perclos, e.blinks))
                appendLine("MAR %.2f  jaw %.2f  yawns5m %d".format(r?.mar ?: 0f, r?.jawOpen ?: 0f, e.yawns5min))
                appendLine("yawΔ %.0f  pitchΔ %.0f  nods5m %d  gaze %.1fs".format(e.yawDelta, e.pitchDelta, e.nods5min, e.gazeAwayS))
            },
            Modifier.padding(8.dp).background(Color(0x99000000)).padding(6.dp),
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
    }

    @Composable private fun BoxScope.AlertBanner(ui: UiState) {
        val a = ui.alert ?: return
        val bg = when (a.level) { 3 -> Color(0xEECC0000); 2 -> Color(0xEEE08000); else -> Color(0xCC1565C0) }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg).padding(16.dp)) {
            Text("Level ${a.level}: ${a.message}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (!ui.online) Text("Offline: local alerts only", Modifier.align(Alignment.TopEnd).padding(8.dp)
            .background(Color(0xAA000000)).padding(4.dp), color = Color.Yellow)
    }

    @Composable private fun Home(ui: UiState) {
        Text("DriveAware", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(if (ui.calibrated) "Calibrated ✓" else "Please calibrate first", color = if (ui.calibrated) Color.Green else Color.Yellow)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.startMonitoring() }, enabled = ui.calibrated, modifier = Modifier.fillMaxWidth()) { Text("Start trip") }
        OutlinedButton(onClick = { vm.startCalibration() }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate (10 s)") }
        OutlinedButton(onClick = { vm.go(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
        Spacer(Modifier.height(12.dp))
        Text("Test mode (ablation)")
        Mode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = ui.mode == m, onClick = { vm.setMode(m) }); Text(m.csv)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ui.showDebug, onCheckedChange = { vm.toggleDebug() }); Text("  Debug overlay")
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
        Text(if (ui.raw?.faceFound == false) "Face not found — check the mount" else "Watching…")
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
        val rules = ui.events.filter { it.source == "rules" }
        Text("Alerts: ${rules.size}")
        rules.groupingBy { it.eventType }.eachCount().forEach { (k, n) -> Text("• $k × $n") }
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
