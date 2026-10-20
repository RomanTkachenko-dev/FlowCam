package org.sappari.air_visualizer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.sappari.air_visualizer.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Optical-change instrument; image displacement is never converted into gas velocity. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController
    private lateinit var processor: FlowProcessor
    private lateinit var converter: YuvFrameConverter
    private val worker = Executors.newSingleThreadExecutor()
    private val storage = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val token = AtomicLong()
    private val uiPending = AtomicBoolean()
    private val destroyed = AtomicBoolean()
    private enum class Phase { PREVIEW, LOCKING, CALIBRATING, LIVE, REPLAY }
    @Volatile private var phase = Phase.PREVIEW
    @Volatile private var options = ProcessingOptions()
    @Volatile private var opacity = .8f
    @Volatile private var rawOnly = false
    @Volatile private var recording = false
    @Volatile private var busy = false
    @Volatile private var lastSession: RecordedSession? = null
    @Volatile private var diagnostic = "Очікування кадру"
    private var baselineCamera: CameraSample? = null // worker-owned
    private var lastUiNs = 0L
    private var lastFrameTimestamp = 0L
    private var smoothedFrameIntervalNs = 0.0
    private var recorded = ArrayList<RecordedFrame>()
    private var recordingCalibration: CalibrationData? = null
    private var recordingMetadata = ""
    private var recordingBytes = 0L
    private var lastRecordedNs = 0L
    private var pendingSave: RecordedSession? = null
    private var pendingExport: RecordedSession? = null
    private var pendingExportOptions: ProcessingOptions? = null
    private var pendingExportAlpha = .8f
    private var pendingExportRaw = false
    private var updatingControls = false
    private val prefs by lazy { getSharedPreferences("airvisualizer-v3", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        window.decorView.windowInsetsController?.hide(WindowInsets.Type.systemBars())
        if (!OpenCVLoader.initLocal()) {
            status("Не вдалося завантажити OpenCV"); binding.buttonStart.isEnabled = false; return
        }
        processor = FlowProcessor()
        converter = YuvFrameConverter()
        options = ProcessingOptions(gain = prefs.getFloat("gain", 8f).toDouble().coerceIn(1.0, 60.0),
            noiseThreshold = prefs.getFloat("threshold", 2f).toDouble().coerceIn(0.0, 5.0),
            lagMs = prefs.getInt("lag", 100).coerceIn(20, 500),
            region = NormalizedRegion(prefs.getFloat("left", .2f), prefs.getFloat("top", .2f),
                prefs.getFloat("right", .8f), prefs.getFloat("bottom", .8f)))
        camera = CameraController(this, worker, { image -> analyze(image) }, { message -> status(message) })
        setupControls()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            camera.open(readCameraSettings())
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        val recent = File(filesDir, "last-session.air")
        if (recent.isFile) storage.execute {
            try {
                val session = recent.inputStream().use(SessionStore::read)
                if (lastSession == null) lastSession = session
                ui { controls() }
            } catch (_: Exception) { /* Failed prior writes are never used as measurement data. */ }
        }
    }

    private fun readCameraSettings() = CameraSettings(
        id = prefs.getString("cameraId", "") ?: "", highResolution = prefs.getBoolean("highResolution", false),
        manual = prefs.getBoolean("manual", false), exposureNs = prefs.getLong("exposure", 10_000_000),
        iso = prefs.getInt("iso", 200), focusDiopters = if (prefs.contains("focus")) prefs.getFloat("focus", 0f) else null)

    private fun setupControls() {
        binding.flowView.region = options.region
        binding.flowView.overlayAlpha = opacity
        binding.flowView.onRegionChanged = { region ->
            options = options.copy(region = region); persistOptions()
            stopMeasurement("Область змінено. Повторіть калібрування")
        }
        binding.modeSpinner.adapter = ArrayAdapter(this, R.layout.spinner_row,
            listOf("Зміни відносно еталона", "Зміни за інтервал часу", "Деформація · експеримент"))
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingControls) return
                options = options.copy(mode = AnalysisMode.values()[position])
                if (phase == Phase.REPLAY) replay()
            }
        }
        setSlider(binding.gainSlider) { value ->
            options = options.copy(gain = value + 1.0)
            binding.gainLabel.text = "Підсилення ×${value + 1}"; persistOptions()
        }
        setSlider(binding.thresholdSlider) { value ->
            options = options.copy(noiseThreshold = value / 10.0)
            binding.thresholdLabel.text = String.format(Locale.US, "Поріг шуму %.1f σ", value / 10.0); persistOptions()
        }
        setSlider(binding.lagSlider) { value ->
            options = options.copy(lagMs = (value + 1) * 20)
            binding.lagLabel.text = "Мінімальний інтервал: ${(value + 1) * 20} мс"; persistOptions()
        }
        setSlider(binding.opacitySlider) { value -> opacity = value / 100f; binding.flowView.overlayAlpha = opacity }
        binding.gainSlider.progress = options.gain.toInt() - 1
        binding.thresholdSlider.progress = (options.noiseThreshold * 10).toInt()
        binding.lagSlider.progress = options.lagMs / 20 - 1
        binding.checkRaw.setOnCheckedChangeListener { _, checked ->
            rawOnly = checked; binding.flowView.showOverlay = !checked
        }
        binding.checkStabilize.setOnCheckedChangeListener { _, checked ->
            if (!updatingControls) {
                options = options.copy(stabilize = checked)
                if (phase != Phase.REPLAY) stopMeasurement("Компенсацію змінено. Повторіть калібрування") else replay()
            }
        }
        binding.checkDenoise.setOnCheckedChangeListener { _, checked ->
            if (!updatingControls) {
                options = options.copy(denoise = checked)
                stopMeasurement("Згладжування змінено. Повторіть калібрування")
            }
        }
        binding.checkRoi.setOnCheckedChangeListener { _, checked ->
            binding.flowView.editingRegion = checked
            if (checked && !updatingControls) {
                stopMeasurement("Обведіть область потоку. Залиште нерухомий фон навколо")
                binding.flowView.editingRegion = true
            }
        }
        binding.buttonStart.setOnClickListener { startCalibration() }
        binding.buttonStop.setOnClickListener {
            val wasReplay = phase == Phase.REPLAY
            stopMeasurement("Перегляд камери")
            if (wasReplay) camera.bind(camera.settings)
        }
        binding.buttonRecord.setOnClickListener {
            worker.execute { if (recording) finishRecording() else startRecording(); ui { controls() } }
        }
        binding.buttonReplay.setOnClickListener { replay() }
        binding.buttonSave.setOnClickListener {
            pendingSave = lastSession
            createDocument("application/octet-stream", "FlowCam-${System.currentTimeMillis()}.air", 20)
        }
        binding.buttonOpen.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 21)
        }
        binding.buttonExport.setOnClickListener {
            pendingExport = lastSession; pendingExportOptions = options.copy()
            pendingExportAlpha = opacity; pendingExportRaw = rawOnly
            createDocument("video/mp4", "FlowCam-${System.currentTimeMillis()}.mp4", 22)
        }
        binding.buttonCamera.setOnClickListener { cameraDialog() }
        binding.buttonDiagnostics.setOnClickListener { showDiagnostics() }
        controls()
    }

    private fun setSlider(slider: SeekBar, change: (Int) -> Unit) {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { if (phase == Phase.REPLAY) replay() }
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { change(progress) }
        })
    }
    private fun persistOptions() {
        val o = options
        prefs.edit().putFloat("gain", o.gain.toFloat()).putFloat("threshold", o.noiseThreshold.toFloat())
            .putInt("lag", o.lagMs).putFloat("left", o.region.left).putFloat("top", o.region.top)
            .putFloat("right", o.region.right).putFloat("bottom", o.region.bottom).apply()
    }
    private fun controls() {
        val isReplay = phase == Phase.REPLAY
        binding.buttonStart.isEnabled = !busy && !recording && phase != Phase.LOCKING && !isReplay
        binding.buttonStop.isEnabled = !busy && phase != Phase.PREVIEW
        binding.buttonRecord.isEnabled = !busy && phase == Phase.LIVE
        binding.buttonRecord.text = if (recording) "Завершити запис" else "Запис досліду · до 10 с"
        val hasSession = lastSession != null
        binding.buttonReplay.isEnabled = !busy && !recording && hasSession
        binding.buttonSave.isEnabled = !busy && !recording && hasSession
        binding.buttonExport.isEnabled = !busy && !recording && hasSession
        binding.buttonOpen.isEnabled = !busy && !recording
        binding.buttonCamera.isEnabled = !busy && !recording && !isReplay
        binding.checkDenoise.isEnabled = !busy && !recording && !isReplay
        binding.checkRoi.isEnabled = !busy && !recording && !isReplay
        binding.checkStabilize.isEnabled = !busy && !recording
        binding.modeSpinner.isEnabled = !busy && !recording
        binding.flowView.editingRegion = binding.checkRoi.isChecked && !isReplay && !busy && !recording
        if (phase != Phase.PREVIEW || recording || busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun startCalibration() {
        if (!camera.ready || busy) { status("Дочекайтеся відкриття камери"); return }
        val current = token.incrementAndGet()
        phase = Phase.LOCKING; binding.checkRoi.isChecked = false
        worker.execute { finishRecording(); processor.stop(); baselineCamera = null }
        controls()
        camera.lockForMeasurement { error ->
            if (current != token.get() || destroyed.get()) return@lockForMeasurement
            if (error != null) { stopMeasurement(error); return@lockForMeasurement }
            worker.execute {
                if (current == token.get()) {
                    baselineCamera = camera.latest; processor.beginCalibration()
                    ui {
                        if (current == token.get()) {
                            phase = Phase.CALIBRATING
                            status("Фен вимкнено. Вимірюємо шум нерухомого фону…"); controls()
                        }
                    }
                }
            }
        }
    }
    private fun stopMeasurement(message: String) {
        token.incrementAndGet(); phase = Phase.PREVIEW
        if (::camera.isInitialized) camera.unlock()
        if (::processor.isInitialized && !worker.isShutdown)
            worker.execute { finishRecording(); processor.stop(); baselineCamera = null }
        status(message); controls()
    }

    private fun analyze(image: ImageProxy) {
        val began = System.nanoTime()
        val current = token.get()
        try {
            if (destroyed.get() || phase == Phase.REPLAY) return
            converter.convert(image)
            val timestamp = image.imageInfo.timestamp
            if (lastFrameTimestamp != 0L && timestamp > lastFrameTimestamp) {
                val interval = (timestamp - lastFrameTimestamp).toDouble()
                smoothedFrameIntervalNs = if (smoothedFrameIntervalNs == 0.0) interval
                    else .9 * smoothedFrameIntervalNs + .1 * interval
            }
            lastFrameTimestamp = timestamp
            val sample = camera.sampleFor(timestamp)
            val p = phase
            var result: ProcessingResult? = null
            if (p == Phase.CALIBRATING || p == Phase.LIVE) {
                if (cameraChanged(sample ?: camera.latest)) {
                    processor.stop(); finishRecording()
                    ui {
                        if (current == token.get()) stopMeasurement("Параметри камери змінилися. Потрібне нове калібрування")
                    }
                } else {
                    result = processor.process(converter.gray, timestamp, options)
                    if (current != token.get()) return
                    if (result.calibrated && p == Phase.CALIBRATING) {
                        ui { if (current == token.get()) { phase = Phase.LIVE; controls() } }
                    }
                }
            }
            if (recording && result?.calibrated == true) captureRecordedFrame(timestamp, sample, result)
            val elapsed = (System.nanoTime() - began) / 1_000_000.0
            val fps = if (smoothedFrameIntervalNs > 0) 1e9 / smoothedFrameIntervalNs else 0.0
            diagnostic = "${converter.gray.cols()}×${converter.gray.rows()} · ${"%.1f".format(Locale.US, fps)} кадр/с · ${"%.1f".format(Locale.US, elapsed)} мс\n" +
                "${result?.quality ?: "Попередній перегляд"} · зсув " +
                "${"%.2f".format(Locale.US, result?.shiftX ?: 0.0)}, ${"%.2f".format(Locale.US, result?.shiftY ?: 0.0)} px\n" +
                "Кадр $timestamp; метадані цього кадру: ${sample?.json() ?: "ще недоступні"}"
            if (began - lastUiNs > 66_000_000 && uiPending.compareAndSet(false, true)) {
                lastUiNs = began
                var base: Bitmap? = null; var overlay: Bitmap? = null
                try {
                    base = Bitmap.createBitmap(converter.rgba.cols(), converter.rgba.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(converter.rgba, base)
                    result?.let {
                        overlay = Bitmap.createBitmap(it.overlay.cols(), it.overlay.rows(), Bitmap.Config.ARGB_8888)
                        Utils.matToBitmap(it.overlay, overlay, true)
                    }
                    val sentBase = base; val sentOverlay = overlay
                    val vectors = result?.vectors ?: emptyList()
                    val message = result?.message
                    val progress = result?.progress ?: 0f
                    val shiftX = if (options.stabilize) (result?.shiftX ?: 0.0).toFloat() else 0f
                    val shiftY = if (options.stabilize) (result?.shiftY ?: 0.0).toFloat() else 0f
                    val frameInfo = diagnostic.substringBefore("\nКадр") + if (recording) "\n● Запис: ${recorded.size} кадрів" else ""
                    runOnUiThread {
                        try {
                            if (current == token.get() && !destroyed.get()) {
                                binding.flowView.submitFrame(sentBase, sentOverlay, vectors, shiftX, shiftY)
                                binding.frameInfo.text = frameInfo
                                if (message != null && !busy) status(message +
                                    if (phase == Phase.CALIBRATING) " · ${(100 * progress).toInt()}%" else "")
                            } else { sentBase.recycle(); sentOverlay?.recycle() }
                        } finally { uiPending.set(false) }
                    }
                } catch (e: Exception) { base?.recycle(); overlay?.recycle(); uiPending.set(false); throw e }
            }
        } catch (e: Exception) {
            diagnostic = "${e.javaClass.simpleName}: ${e.message}"
            ui { status("Помилка кадру: ${e.message}") }
        } finally { image.close() }
    }
    private fun cameraChanged(sample: CameraSample?): Boolean {
        val baseline = baselineCamera ?: return false
        if (sample == null) return false
        return (sample.iso != null && baseline.iso != null && sample.iso != baseline.iso) ||
            (sample.exposure != null && baseline.exposure != null &&
                kotlin.math.abs(sample.exposure - baseline.exposure) > maxOf(100_000L, baseline.exposure / 20)) ||
            (sample.focus != null && baseline.focus != null && kotlin.math.abs(sample.focus - baseline.focus) > .03f) ||
            (baseline.aeLocked == true && sample.aeLocked == false) ||
            (baseline.awbLocked == true && sample.awbLocked == false)
    }
    private fun startRecording() {
        if (phase != Phase.LIVE) return
        recordingCalibration = processor.exportCalibration() ?: return
        recordingMetadata = JSONObject().apply {
            put("app", "FlowCam 0.3.0"); put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("camera", camera.description); put("capture", camera.latest?.json() ?: JSONObject.NULL)
            put("processing", optionsJson(options)); put("measurement", "image deformation; not gas velocity")
            put("luma", "lossless Y after crop/rotation/area resize; before FlowProcessor")
            put("preview", "JPEG for color display only; never measurement"); put("sampleIntervalNs", 125_000_000L)
        }.toString()
        recorded = ArrayList(); recordingBytes = 0; lastRecordedNs = 0; recording = true
        ui { status("Записуємо дані: змінюйте режим фена протягом досліду"); controls() }
    }
    private fun captureRecordedFrame(timestamp: Long, sample: CameraSample?, result: ProcessingResult) {
        if (lastRecordedNs != 0L && timestamp - lastRecordedNs < 125_000_000L) return
        val gray = converter.gray
        val bytes = ByteArray(gray.rows() * gray.cols()); gray.get(0, 0, bytes)
        val bitmap = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
        val jpeg = try {
            Utils.matToBitmap(converter.rgba, bitmap)
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out))
                out.toByteArray().takeIf { it.size <= SessionStore.MAX_PREVIEW_BYTES }
            }
        } finally { bitmap.recycle() }
        val needed = bytes.size.toLong() + (jpeg?.size ?: 0)
        if (recordingBytes + needed > SessionStore.MAX_RECORDING_BYTES || recorded.size >= SessionStore.MAX_FRAMES ||
            (recorded.isNotEmpty() && timestamp - recorded.first().timestampNs >= 10_000_000_000L)) {
            finishRecording(); return
        }
        val frameMetadata = JSONObject().apply {
            put("cameraExactFrame", sample?.json() ?: JSONObject.NULL)
            put("options", optionsJson(options))
            put("quality", result.quality); put("message", result.message)
            put("imageShiftX_px", result.shiftX); put("imageShiftY_px", result.shiftY)
            put("meanResidual_lumaLevels", result.meanSignal)
        }.toString()
        recorded.add(RecordedFrame(timestamp, bytes, frameMetadata, jpeg))
        lastRecordedNs = timestamp; recordingBytes += needed
    }
    private fun finishRecording() {
        if (!recording) return
        recording = false
        val calibration = recordingCalibration; recordingCalibration = null
        if (calibration == null || recorded.isEmpty()) {
            ui { controls(); status("Запис порожній — дочекайтеся кадрів після калібрування") }; return
        }
        val session = RecordedSession(calibration, recordingMetadata, recorded.toList())
        recorded = ArrayList(); lastSession = session
        storage.execute {
            val temp = File(filesDir, "last-session.air.part")
            try {
                temp.outputStream().use { SessionStore.write(it, session) }
                java.nio.file.Files.move(temp.toPath(), File(filesDir, "last-session.air").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) { ui { status("Дослід у пам’яті, але автозбереження не вдалося: ${e.message}") } }
        }
        ui { controls(); status("Збережено ${session.frames.size} кадрів. Можна повторити або експортувати") }
    }

    private fun replay() {
        val session = lastSession ?: return
        if (busy || recording) return
        val current = token.incrementAndGet(); phase = Phase.REPLAY; camera.suspendCamera()
        updatingControls = true
        options = options.copy(denoise = session.calibration.denoise, region = session.calibration.region)
        binding.checkDenoise.isChecked = options.denoise; binding.checkRoi.isChecked = false
        binding.flowView.region = options.region; updatingControls = false; controls()
        worker.execute {
            try { processor.importCalibration(session.calibration); replayFrame(session, 0, current) }
            catch (e: Exception) { ui { status("Повторення не вдалося: ${e.message}") } }
        }
    }
    private fun replayFrame(session: RecordedSession, index: Int, current: Long) {
        if (token.get() != current || destroyed.get()) return
        val frame = session.frames[index]
        val gray = Mat(session.calibration.height, session.calibration.width, CvType.CV_8UC1)
        try {
            gray.put(0, 0, frame.luma)
            val result = processor.process(gray, frame.timestampNs, options)
            val base = sessionBitmap(frame, gray)
            val overlay = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(result.overlay, overlay, true)
            val shiftX = if (options.stabilize) result.shiftX.toFloat() else 0f
            val shiftY = if (options.stabilize) result.shiftY.toFloat() else 0f
            runOnUiThread {
                if (current == token.get() && !destroyed.get()) {
                    binding.flowView.submitFrame(base, overlay, result.vectors, shiftX, shiftY)
                    if (!busy) status("Повтор ${index + 1}/${session.frames.size} · ${result.message}")
                    val elapsed = (frame.timestampNs - session.frames.first().timestampNs) / 1e9
                    val nextText = if (index == session.frames.lastIndex) " · завершено; змініть параметри для повтору" else ""
                    binding.frameInfo.text = "${"%.2f".format(Locale.US, elapsed)} с · ${result.quality}$nextText"
                } else { base.recycle(); overlay.recycle() }
            }
        } catch (e: Exception) { ui { status("Помилка повторення: ${e.message}") }; return }
        finally { gray.release() }
        if (index < session.frames.lastIndex) {
            val delay = ((session.frames[index + 1].timestampNs - frame.timestampNs) / 1_000_000).coerceIn(1, 1000)
            handler.postDelayed({
                if (!worker.isShutdown && current == token.get()) worker.execute { replayFrame(session, index + 1, current) }
            }, delay)
        }
    }
    private fun sessionBitmap(frame: RecordedFrame, gray: Mat): Bitmap {
        frame.previewJpeg?.let { bytes ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth == gray.cols() && bounds.outHeight == gray.rows())
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        val rgba = Mat()
        try {
            Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
            return Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
        } finally { rgba.release() }
    }
    private fun createDocument(type: String, name: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            this.type = type; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name)
        }, code)
    }
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            pendingSave = null; pendingExport = null; pendingExportOptions = null; return
        }
        when (requestCode) {
            20 -> pendingSave?.let { session ->
                pendingSave = null
                ioOperation("Зберігаємо дослід…") {
                    contentResolver.openOutputStream(uri, "wt")?.use { SessionStore.write(it, session) }
                        ?: error("Не вдалося відкрити файл")
                    "Дані досліду збережено"
                }
            }
            21 -> {
                stopMeasurement("Читаємо дослід…"); camera.suspendCamera()
                phase = Phase.REPLAY
                ioOperation("Читаємо дослід…") {
                    val loaded = contentResolver.openInputStream(uri)?.use(SessionStore::read) ?: error("Файл не відкрився")
                    lastSession = loaded
                    "Відкрито ${loaded.frames.size} кадрів. Натисніть «Повторити останній»"
                }
            }
            22 -> {
                val session = pendingExport ?: return
                val exportOptions = pendingExportOptions ?: options.copy()
                pendingExport = null; pendingExportOptions = null
                val alpha = pendingExportAlpha; val raw = pendingExportRaw
                ioOperation("Створюємо MP4 з поточними параметрами…") {
                    exportVideo(uri, session, exportOptions, alpha, raw)
                    "MP4 збережено з часовою шкалою досліду"
                }
            }
        }
    }
    private fun ioOperation(message: String, action: () -> String) {
        busy = true; controls(); status(message)
        storage.execute {
            val result = try { action() } catch (e: Exception) { "Не вдалося: ${e.message}" }
            busy = false; ui { controls(); status(result) }
        }
    }
    private fun exportVideo(uri: Uri, session: RecordedSession, desired: ProcessingOptions, alpha: Float, raw: Boolean) {
        val output = File.createTempFile("air-export-", ".mp4", cacheDir)
        val engine = FlowProcessor()
        val gray = Mat(session.calibration.height, session.calibration.width, CvType.CV_8UC1)
        try {
            engine.importCalibration(session.calibration)
            val exportOptions = desired.copy(region = session.calibration.region, denoise = session.calibration.denoise)
            OverlayVideoExporter(gray.cols(), gray.rows(), output).use { encoder ->
                session.frames.forEachIndexed { index, frame ->
                    if (destroyed.get()) error("Експорт перервано: застосунок закрито")
                    gray.put(0, 0, frame.luma)
                    val result = engine.process(gray, frame.timestampNs, exportOptions)
                    val base = sessionBitmap(frame, gray)
                    val overlay = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
                    var rendered: Bitmap? = null
                    try {
                        Utils.matToBitmap(result.overlay, overlay, true)
                        rendered = FlowCanvasView.renderFrame(base, if (raw) null else overlay,
                            if (raw) emptyList() else result.vectors, exportOptions.region, alpha,
                            if (exportOptions.stabilize) result.shiftX.toFloat() else 0f,
                            if (exportOptions.stabilize) result.shiftY.toFloat() else 0f)
                        val canvas = android.graphics.Canvas(rendered)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE; textSize = gray.cols() / 55f
                        }
                        canvas.drawRect(0f, gray.rows() - paint.textSize * 3, gray.cols().toFloat(),
                            gray.rows().toFloat(), android.graphics.Paint().apply { color = 0xD0000000.toInt() })
                        canvas.drawText(result.message, 8f, gray.rows() - paint.textSize * 1.6f, paint)
                        val time = (frame.timestampNs - session.frames.first().timestampNs) / 1e9
                        canvas.drawText("${"%.2f".format(Locale.US, time)} с · ${result.quality}", 8f, gray.rows() - 6f, paint)
                        encoder.addFrame(rendered, frame.timestampNs)
                    } finally { rendered?.recycle(); overlay.recycle(); base.recycle() }
                    if (index % 10 == 0) ui { status("Експорт MP4: ${index + 1}/${session.frames.size}") }
                }
                encoder.finish()
            }
            contentResolver.openOutputStream(uri, "wt")?.use { target -> output.inputStream().use { it.copyTo(target) } }
                ?: error("Не вдалося відкрити файл призначення")
        } finally { engine.close(); gray.release(); output.delete() }
    }
    private fun optionsJson(o: ProcessingOptions) = JSONObject().apply {
        put("mode", o.mode.name); put("gain", o.gain); put("noiseSigma", o.noiseThreshold)
        put("stabilize", o.stabilize); put("denoise", o.denoise); put("lagMs", o.lagMs)
        put("left", o.region.left); put("top", o.region.top); put("right", o.region.right); put("bottom", o.region.bottom)
    }

    private fun cameraDialog() {
        val initial = camera.settings
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        fun label(text: String) { column.addView(TextView(this).apply { this.text = text }) }
        label("Фокус у діоптріях, 0 = нескінченність. Зміна потребує нового еталона.")
        val cameraChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, camera.choices.map { it.second })
            setSelection(camera.choices.indexOfFirst { it.first == initial.id }.coerceAtLeast(0))
        }
        column.addView(cameraChoice)
        val high = CheckBox(this).apply { text = "Знімання 1080p (аналіз до 960 px)"; isChecked = initial.highResolution }
        column.addView(high)
        val manual = CheckBox(this).apply { text = "Ручні витримка та ISO"; isChecked = initial.manual; isEnabled = camera.manualSupported }
        column.addView(manual)
        label("Діапазони — для поточної камери. Після зміни об’єктива відкрийте це вікно повторно.")
        fun input(caption: String, value: String): EditText {
            label(caption)
            return EditText(this).apply {
                setText(value); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                column.addView(this)
            }
        }
        val exposure = input("Витримка, мс · ${camera.exposureRange?.let { "${it.lower / 1e6}…${it.upper / 1e6}" } ?: "невідомо"}",
            (initial.exposureNs / 1e6).toString())
        val iso = input("ISO · ${camera.isoRange ?: "невідомо"}", initial.iso.toString())
        val focus = input("Фокус 0…${camera.maxFocus}; порожньо = автофокус", initial.focusDiopters?.toString() ?: "")
        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this).setTitle("Камера S25 / Camera2").setView(scroll)
            .setNegativeButton("Скасувати", null).setPositiveButton("Застосувати", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ms = exposure.text.toString().replace(',', '.').toDoubleOrNull()
                val sensitivity = iso.text.toString().toIntOrNull()
                val focusText = focus.text.toString().trim().replace(',', '.')
                val focusValue = focusText.toFloatOrNull()
                if (ms == null || !ms.isFinite() || ms <= 0 || ms > 10_000 || sensitivity == null || sensitivity <= 0 ||
                    (focusText.isNotEmpty() && (focusValue == null || !focusValue.isFinite() || focusValue < 0))) {
                    label("Перевірте введені числа"); return@setOnClickListener
                }
                val next = CameraSettings(camera.choices.getOrNull(cameraChoice.selectedItemPosition)?.first ?: initial.id,
                    high.isChecked, manual.isChecked, (ms * 1e6).toLong(), sensitivity, focusValue)
                val pref = prefs.edit().putString("cameraId", next.id).putBoolean("highResolution", next.highResolution)
                    .putBoolean("manual", next.manual).putLong("exposure", next.exposureNs).putInt("iso", next.iso)
                if (focusValue == null) pref.remove("focus") else pref.putFloat("focus", focusValue)
                pref.apply(); stopMeasurement("Застосовуємо параметри камери…"); camera.bind(next); dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun showDiagnostics() {
        val report = """FlowCam 0.3.0
${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}
${camera.description}
$diagnostic

Закріпіть телефон; освітлений текстурований фон у фокусі; фен вимкнено. Виберіть область струменя, залишаючи навколо нерухомий контрольний фон. Калібруйте, дочекайтеся еталона і лише тоді ввімкніть фен.

Поріг відкидає виміряний шум; підсилення змінює видимість. Відсутність сигналу не означає відсутність повітря. Холодний однорідний потік може не давати оптичного контрасту.

Рух об’єктів, мерехтіння екрана й зміни освітлення можуть створювати схожий сигнал. Компенсується лише глобальний зсув за зовнішньою областю, не поворот чи паралакс.

Стрілки: зсув текстури відносно еталона, px; довжина на екрані ×10. Це не вектори швидкості газу.

Запис: до 10 с, до 8 кадрів/с і 40 MiB. Y-кадри без втрат після зменшення до 960 px; кольорові JPEG тільки для перегляду. Запис містить не всі кадри живого режиму. Фактичний крок часового порівняння може бути більшим за заданий.

Обчислення локальні. Навченої ШІ-моделі немає. Чутливість і камеру на S25 потрібно перевірити фізичним дослідом."""
        AlertDialog.Builder(this).setTitle("Діагностика та робота з приладом").setMessage(report)
            .setPositiveButton("Копіювати") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FlowCam", report))
            }.setNegativeButton("Закрити", null).show()
    }
    private fun status(message: String) { binding.statusText.text = message }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!destroyed.get()) action() } }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera.open(readCameraSettings())
        else if (code == 10) status("Для знімання надайте доступ до камери в налаштуваннях Android")
    }
    override fun onStop() {
        if (::camera.isInitialized && !isChangingConfigurations) {
            // Document pickers also stop the Activity. Never reuse a potentially obsolete reference.
            if (phase != Phase.REPLAY) stopMeasurement("Повернулися до камери: створіть новий еталон")
            else token.incrementAndGet()
        }
        super.onStop()
    }
    override fun onDestroy() {
        destroyed.set(true); token.incrementAndGet(); handler.removeCallbacksAndMessages(null)
        if (::camera.isInitialized) camera.close()
        if (::processor.isInitialized) worker.execute {
            finishRecording(); processor.close(); converter.close(); storage.shutdown()
        } else storage.shutdown()
        worker.shutdown(); binding.flowView.clearFrame(); super.onDestroy()
    }
}
