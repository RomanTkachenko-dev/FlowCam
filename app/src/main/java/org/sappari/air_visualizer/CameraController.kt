package org.sappari.air_visualizer

import android.hardware.camera2.*
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executor

data class CameraSettings(
    val id: String = "",
    val highResolution: Boolean = false,
    val manual: Boolean = false,
    val exposureNs: Long = 10_000_000,
    val iso: Int = 200,
    val focusDiopters: Float? = null
)

data class CameraSample(
    val timestamp: Long,
    val exposure: Long?,
    val iso: Int?,
    val focus: Float?,
    val ae: Int?,
    val awb: Int?,
    val af: Int?,
    val aeLocked: Boolean?,
    val awbLocked: Boolean?,
    val ois: Int?,
    val eis: Int?
) {
    fun json(): String = JSONObject().apply {
        put("sensorTimestampNs", timestamp)
        put("exposureNs", exposure ?: JSONObject.NULL)
        put("iso", iso ?: JSONObject.NULL)
        put("focusDiopters", focus ?: JSONObject.NULL)
        put("aeState", ae ?: JSONObject.NULL); put("awbState", awb ?: JSONObject.NULL)
        put("afState", af ?: JSONObject.NULL)
        put("aeLocked", aeLocked ?: JSONObject.NULL); put("awbLocked", awbLocked ?: JSONObject.NULL)
        put("ois", ois ?: JSONObject.NULL); put("eis", eis ?: JSONObject.NULL)
    }.toString()
}

/** All commands run on the main thread. Capture results are shared under a lock. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraController(
    private val activity: AppCompatActivity,
    private val analyzerExecutor: Executor,
    private val analyzer: ImageAnalysis.Analyzer,
    private val onStatus: (String) -> Unit
) {
    private val main = ContextCompat.getMainExecutor(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraInfo: Camera2CameraInfo? = null
    private var token = 0L
    @Volatile private var cameraEpoch = 0L
    private val guard = Any()
    private val samples = LinkedHashMap<Long, CameraSample>()
    @Volatile var latest: CameraSample? = null
        private set
    var settings = CameraSettings()
        private set
    var description = "Камеру ще не відкрито"
        private set
    var choices: List<Pair<String, String>> = emptyList()
        private set
    var ready = false
        private set
    val manualSupported: Boolean get() = cameraInfo?.getCameraCharacteristic(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
    )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val exposureRange get() = cameraInfo?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val isoRange get() = cameraInfo?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val maxFocus get() = cameraInfo?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    private val aeLock get() = cameraInfo?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
    private val awbLock get() = cameraInfo?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

    fun open(initial: CameraSettings) {
        settings = initial
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (activity.isDestroyed) return@addListener
            try {
                provider = future.get()
                choices = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider!!.availableCameraInfos).map {
                    val info = Camera2CameraInfo.from(it)
                    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    info.cameraId to "Камера ${info.cameraId} · ${focal?.joinToString() ?: "?"} мм"
                }
                bind(settings)
            } catch (e: Exception) { onStatus("Камера недоступна: ${e.message}") }
        }, main)
    }

    fun bind(requested: CameraSettings) {
        val p = provider ?: return
        token++
        val epoch = ++cameraEpoch
        ready = false
        try {
            p.unbindAll()
            val back = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos)
            val info = back.firstOrNull { Camera2CameraInfo.from(it).cameraId == requested.id } ?: back.first()
            val ci = Camera2CameraInfo.from(info)
            cameraInfo = ci
            settings = requested.copy(
                id = ci.cameraId,
                manual = requested.manual && manualSupported,
                exposureNs = exposureRange?.clamp(requested.exposureNs) ?: requested.exposureNs,
                iso = isoRange?.clamp(requested.iso) ?: requested.iso,
                focusDiopters = requested.focusDiopters?.coerceIn(0f, maxFocus)
            )
            synchronized(guard) { samples.clear(); latest = null }
            val selector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { list -> list.filter { Camera2CameraInfo.from(it).cameraId == ci.cameraId } }.build()
            val builder = ImageAnalysis.Builder()
                .setTargetResolution(if (settings.highResolution) Size(1920, 1080) else Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            val interop = Camera2Interop.Extender(builder)
            val fps = ci.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            fps?.firstOrNull { it.lower == 30 && it.upper == 30 }?.let {
                interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            if (ci.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true) {
                interop.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (ci.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true) {
                interop.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            interop.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                    if (epoch != cameraEpoch) return
                    val time = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val sample = CameraSample(time, r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        r.get(CaptureResult.SENSOR_SENSITIVITY), r.get(CaptureResult.LENS_FOCUS_DISTANCE),
                        r.get(CaptureResult.CONTROL_AE_STATE), r.get(CaptureResult.CONTROL_AWB_STATE),
                        r.get(CaptureResult.CONTROL_AF_STATE), r.get(CaptureResult.CONTROL_AE_LOCK),
                        r.get(CaptureResult.CONTROL_AWB_LOCK), r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                        r.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
                    synchronized(guard) {
                        samples[time] = sample
                        while (samples.size > 90) samples.remove(samples.keys.first())
                        latest = sample
                    }
                }
            })
            val analysis = builder.build()
            analysis.setAnalyzer(analyzerExecutor, analyzer)
            camera = p.bindToLifecycle(activity, selector, analysis)
            description = "Camera ID ${ci.cameraId}; ${choices.firstOrNull { it.first == ci.cameraId }?.second}; " +
                "manualSensor=$manualSupported; AE lock=$aeLock; AWB lock=$awbLock"
            applyOptions(false) {
                ready = it == null
                onStatus(it ?: "Закріпіть телефон, вимкніть фен і натисніть «Калібрувати»")
            }
        } catch (e: Exception) { onStatus("Помилка камери: ${e.message}") }
    }

    fun sampleFor(timestamp: Long): CameraSample? = synchronized(guard) { samples[timestamp] }

    private fun applyOptions(lock: Boolean, done: (String?) -> Unit) {
        val cam = camera ?: return done("Камера не готова")
        val options = CaptureRequestOptions.Builder()
        if (settings.manual) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureNs)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
        } else if (aeLock) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
        if (awbLock) options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
        settings.focusDiopters?.let {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        val future = Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        val requestToken = token
        future.addListener({
            if (requestToken == token) {
                try { future.get(); done(null) }
                catch (e: Exception) { done("Налаштування не застосовані: ${e.cause?.message ?: e.message}") }
            }
        }, main)
    }

    fun lockForMeasurement(done: (String?) -> Unit) {
        if (!ready) return done("Камера ще не готова")
        val cam = camera ?: return done("Камера недоступна")
        val current = ++token
        var finished = false
        fun finish(error: String?) {
            if (current != token || finished) return
            finished = true; done(error)
        }
        handler.postDelayed({ finish("Камера не підтвердила стабільні налаштування. Перевірте світло, фокус або ручний режим") }, 8500)
        fun verify() {
            if (current != token || finished) return
            onStatus("Перевірка фактичної експозиції, фокусу й блокувань…")
            val firstTimestamp = latest?.timestamp ?: 0
            var previous: CameraSample? = null
            var stable = 0
            val check = object : Runnable {
                override fun run() {
                    if (current != token || finished) return
                    val s = latest
                    if (s != null && s.timestamp > firstTimestamp && s.timestamp != previous?.timestamp) {
                        val prev = previous
                        val locks = (settings.manual || !aeLock || (s.aeLocked == true &&
                            (s.ae == null || s.ae == CaptureResult.CONTROL_AE_STATE_LOCKED))) &&
                            (!awbLock || (s.awbLocked == true &&
                                (s.awb == null || s.awb == CaptureResult.CONTROL_AWB_STATE_LOCKED)))
                        val manualActual = (!settings.manual || (s.iso == settings.iso &&
                            s.exposure != null && kotlin.math.abs(s.exposure - settings.exposureNs) <=
                            maxOf(100_000L, settings.exposureNs / 20))) &&
                            (settings.focusDiopters == null || (s.focus != null &&
                                kotlin.math.abs(s.focus - settings.focusDiopters!!) <= .03f)) &&
                            (s.ois == null || s.ois == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF) &&
                            (s.eis == null || s.eis == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        val stationary = s.exposure != null && s.iso != null && prev != null &&
                            s.iso == prev.iso && s.exposure == prev.exposure &&
                            (s.focus == null || prev.focus == null || kotlin.math.abs(s.focus - prev.focus) < .01f)
                        stable = if (locks && manualActual && stationary) stable + 1 else 0
                        previous = s
                        if (stable >= 8) return finish(null)
                    }
                    handler.postDelayed(this, 60)
                }
            }
            handler.post(check)
        }
        fun lock() = applyOptions(true) { error -> if (error != null) finish(error) else verify() }
        if (settings.focusDiopters != null || maxFocus == 0f) lock()
        else {
            onStatus("Фокусування на фоні…")
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
            val future = cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build())
            future.addListener({
                if (current != token || finished) return@addListener
                try {
                    if (future.get().isFocusSuccessful) lock()
                    else finish("Фокус не підтверджено. Додайте світло або задайте ручний фокус")
                } catch (e: Exception) { finish("Не вдалося зафіксувати фокус: ${e.message}") }
            }, main)
        }
    }

    fun unlock() {
        token++
        camera?.cameraControl?.cancelFocusAndMetering()
        if (camera != null) applyOptions(false) { error -> error?.let(onStatus) }
    }

    fun suspendCamera() { token++; cameraEpoch++; provider?.unbindAll(); camera = null; ready = false }
    fun close() { suspendCamera(); handler.removeCallbacksAndMessages(null) }
}
