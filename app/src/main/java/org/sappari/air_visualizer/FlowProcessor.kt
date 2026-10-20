package org.sappari.air_visualizer

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class AnalysisMode { REFERENCE, TEMPORAL, DEFORMATION }

data class NormalizedRegion(
    val left: Float = .2f, val top: Float = .2f,
    val right: Float = .8f, val bottom: Float = .8f
)

data class ProcessingOptions(
    val mode: AnalysisMode = AnalysisMode.REFERENCE,
    val gain: Double = 8.0,
    val noiseThreshold: Double = 2.0,
    val stabilize: Boolean = true,
    val denoise: Boolean = true,
    val lagMs: Int = 100,
    val region: NormalizedRegion = NormalizedRegion()
)

/** Image displacement in pixels, not air velocity; confidence is a heuristic, not a probability. */
data class FlowVector(val x: Float, val y: Float, val dx: Float, val dy: Float, val confidence: Float)

/** overlay is borrowed RGBA memory and is valid only until the next process/close call. */
data class ProcessingResult(
    val overlay: Mat,
    val vectors: List<FlowVector>,
    val message: String,
    val quality: String,
    val calibrated: Boolean,
    val progress: Float,
    val meanSignal: Double,
    val shiftX: Double,
    val shiftY: Double
)

data class CalibrationData(
    val width: Int, val height: Int,
    val reference: FloatArray, val variance: FloatArray,
    val denoise: Boolean, val region: NormalizedRegion
)

/**
 * A conservative optical-change processor. Control pixels are outside the user ROI.
 * All OpenCV matrices and full-frame byte buffers are reused. Call on one worker thread;
 * synchronized state methods also make reset/import safe when requested by the UI.
 * No automatic reference updating, gas-velocity inference, or neural reconstruction occurs.
 */
class FlowProcessor : AutoCloseable {
    private val frame = Mat()
    private val aligned = Mat()
    private val reference8 = Mat()
    private val reference32 = Mat()
    private val overlay = Mat()
    private val smallFrame = Mat()
    private val smallReference = Mat()
    private val controlMask = Mat()
    private val localMask = Mat()
    private val transform = Mat(2, 3, CvType.CV_64F)
    private val corners = MatOfPoint()
    private val controlPoints = MatOfPoint2f()
    private val localPoints = MatOfPoint2f()
    private val tracked = MatOfPoint2f()
    private val returned = MatOfPoint2f()
    private val forwardStatus = MatOfByte()
    private val backwardStatus = MatOfByte()
    private val forwardError = MatOfFloat()
    private val backwardError = MatOfFloat()
    private val termination = TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 20, .01)

    private var width = 0
    private var height = 0
    private var smallWidth = 0
    private var smallHeight = 0
    private var scaleX = 1.0
    private var scaleY = 1.0
    private var roi = Rect()
    private var currentRegion = NormalizedRegion()
    private var bytes = ByteArray(0)
    private var rgba = ByteArray(0)
    private var mean = FloatArray(0)
    private var variance = FloatArray(0)
    private var m2 = FloatArray(0)
    private var initial = ByteArray(0)
    private var ready = false
    private var requested = false
    private var calibrationCount = 0
    private var calibrationStart = 0L
    private var calibrationDenoise = true
    private var lastTimestamp = Long.MIN_VALUE
    private var lastHistoryTimestamp = Long.MIN_VALUE
    private var historyCursor = 0
    private val history = ArrayList<HistoryFrame>(36)
    private var closed = false

    private data class HistoryFrame(var timestamp: Long, val pixels: ByteArray, var offset: Double)
    private data class Registration(
        val x: Double = 0.0, val y: Double = 0.0,
        val valid: Boolean = false, val count: Int = 0, val scatter: Double = 0.0
    )

    @Synchronized fun beginCalibration() {
        check(!closed)
        resetReference()
        requested = true
    }

    @Synchronized fun stop() {
        resetReference()
        requested = false
    }

    @Synchronized fun exportCalibration(): CalibrationData? = if (!ready) null else CalibrationData(
        width, height, mean.copyOf(), variance.copyOf(), calibrationDenoise, currentRegion
    )

    @Synchronized fun importCalibration(data: CalibrationData) {
        check(!closed)
        require(data.width in 8..8192 && data.height in 8..8192)
        val size = data.width.toLong() * data.height
        require(size <= 16_777_216 && data.reference.size.toLong() == size && data.variance.size.toLong() == size)
        require(data.reference.all { it.isFinite() && it in 0f..255f })
        require(data.variance.all { it.isFinite() && it >= 0f && it <= 65_025f })
        ensureSize(data.width, data.height)
        resetReference()
        currentRegion = sanitizeRegion(data.region)
        rebuildMasks()
        data.reference.copyInto(mean)
        data.variance.copyInto(variance)
        calibrationDenoise = data.denoise
        buildReferenceImages()
        ready = true
        requested = false
    }

    @Synchronized fun process(gray: Mat, timestampNs: Long, options: ProcessingOptions): ProcessingResult {
        check(!closed)
        require(!gray.empty() && gray.type() == CvType.CV_8UC1) { "Expected 8-bit single-channel luminance" }
        ensureSize(gray.cols(), gray.rows())
        rgba.fill(0)
        overlay.setTo(Scalar.all(0.0))
        val sanitized = sanitizeRegion(options.region)
        if (sanitized != currentRegion) {
            currentRegion = sanitized
            rebuildMasks()
            clearHistory()
            if (ready || calibrationCount > 0) detectFeatures()
        }
        if (timestampNs <= lastTimestamp && lastTimestamp != Long.MIN_VALUE) {
            clearHistory()
            if (requested) { calibrationCount = 0; calibrationStart = 0L }
        }
        lastTimestamp = timestampNs
        if (ready && options.denoise != calibrationDenoise) {
            resetReference()
            requested = false
            return result("Змінено фільтрацію. Калібруйте знову", "Потрібен еталон")
        }
        if (requested && calibrationCount > 0 && options.denoise != calibrationDenoise) {
            calibrationCount = 0
            calibrationStart = 0L
        }
        if (options.denoise) Imgproc.GaussianBlur(gray, frame, Size(3.0, 3.0), .6)
        else gray.copyTo(frame)
        Imgproc.resize(frame, smallFrame, Size(smallWidth.toDouble(), smallHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        frame.get(0, 0, bytes)

        if (requested) return calibrate(timestampNs, options)
        if (!ready) return result("Вимкніть потік і натисніть «Калібрувати»", "Очікування еталона")

        val registration = register()
        if (options.stabilize && !registration.valid) {
            clearHistory()
            return result("Ненадійний контрольний фон. Зменште область або закріпіть телефон", "Вимірювання призупинене")
        }
        if (options.stabilize && hypot(registration.x, registration.y) > 12.0) {
            clearHistory()
            return result("Камера сильно змістилася. Калібруйте знову", "Вимірювання призупинене", registration = registration)
        }
        if (options.stabilize) {
            transform.put(0, 0, 1.0, 0.0, -registration.x, 0.0, 1.0, -registration.y)
            Imgproc.warpAffine(frame, aligned, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        } else frame.copyTo(aligned)
        aligned.get(0, 0, bytes)
        val control = controlStatistics(bytes, mean)
        val offset = control.first
        if (abs(offset) > 8.0 || control.second > 10.0) {
            clearHistory()
            return result("Змінилося освітлення або фон. Перевірте сцену та еталон", "Вимірювання призупинене", registration = registration)
        }

        val lagNs = options.lagMs.coerceIn(20, 1000) * 1_000_000L
        val past = if (options.mode == AnalysisMode.TEMPORAL) nearestHistory(timestampNs - lagNs) else null
        if (options.mode == AnalysisMode.TEMPORAL && past == null) {
            remember(timestampNs, offset)
            return result("Накопичення кадрів для інтервалу ${options.lagMs} мс", "Очікування кадру", registration = registration)
        }
        val threshold = options.noiseThreshold.takeIf { it.isFinite() }?.coerceIn(0.0, 8.0) ?: 2.0
        val gain = options.gain.takeIf { it.isFinite() }?.coerceIn(.1, 80.0) ?: 8.0
        var signalSum = 0.0
        var k = 0
        for (y in roi.y until roi.y + roi.height) {
            var i = y * width + roi.x
            for (x in roi.x until roi.x + roi.width) {
                val residual = if (past == null) (bytes[i].toInt() and 255) - mean[i] - offset
                else (bytes[i].toInt() and 255) - (past.pixels[k].toInt() and 255) - (offset - past.offset)
                val signal = FlowMath.thresholdResidual(residual, variance[i].toDouble(), threshold)
                val alpha = (abs(signal) * gain * 5.0).toInt().coerceIn(0, 225)
                if (alpha > 0) {
                    val p = i * 4
                    if (signal >= 0) {
                        rgba[p] = 255.toByte(); rgba[p + 1] = 100.toByte(); rgba[p + 2] = 35
                    } else {
                        rgba[p] = 35; rgba[p + 1] = 155.toByte(); rgba[p + 2] = 255.toByte()
                    }
                    rgba[p + 3] = alpha.toByte()
                }
                signalSum += abs(signal)
                i++; k++
            }
        }
        overlay.put(0, 0, rgba)
        val vectors = if (options.mode == AnalysisMode.DEFORMATION) localDeformation(threshold) else emptyList()
        remember(timestampNs, offset)
        val message = when {
            options.mode == AnalysisMode.DEFORMATION && vectors.size < 6 -> "Мало зсувів вище порога: слабкий сигнал або недостатня текстура"
            options.mode == AnalysisMode.DEFORMATION -> "Експериментально: зміщення фону в пікселях, не швидкість повітря"
            options.mode == AnalysisMode.TEMPORAL -> "Зміни за ${((timestampNs - (past?.timestamp ?: timestampNs)) / 1_000_000L)} мс; червоне +, синє −"
            else -> "Еталон зафіксовано; червоне +, синє − зміна яскравості"
        }
        val quality = when {
            !options.stabilize -> "Компенсацію руху вимкнено"
            abs(offset) > 3.0 -> "Невелика зміна освітлення"
            else -> "Контроль фону: ${registration.count} точок"
        }
        return result(message, quality, vectors, signalSum / max(1, roi.area().toInt()), registration)
    }

    private fun calibrate(timestampNs: Long, options: ProcessingOptions): ProcessingResult {
        if (calibrationCount == 0) {
            frame.copyTo(reference8)
            smallFrame.copyTo(smallReference)
            detectFeatures()
            if (controlPoints.total() < 12) {
                return result("Потрібен різкий текстурний фон навколо вибраної області", "Недостатньо контрольних точок")
            }
            bytes.copyInto(initial)
            for (i in bytes.indices) { mean[i] = (bytes[i].toInt() and 255).toFloat(); m2[i] = 0f }
            calibrationCount = 1
            calibrationStart = timestampNs
            calibrationDenoise = options.denoise
            return result("Калібрування: тримайте телефон нерухомо, потік вимкнений", "Збір еталона")
        }
        val reg = register()
        val stats = controlStatistics(bytes, mean)
        // Never align calibration samples: this avoids interpolation-generated variance.
        val stable = reg.valid && hypot(reg.x, reg.y) <= .6 && abs(stats.first) <= 2.5 && stats.second <= 5.0
        if (!stable) {
            // Require a new uninterrupted interval, not 30 selected frames spread over motion.
            calibrationCount = 0
            calibrationStart = 0L
            return result("Нестабільна сцена. Калібрування почнеться після стабілізації", "Рух або зміна освітлення", registration = reg)
        }
        calibrationCount++
        for (i in bytes.indices) {
            val value = (bytes[i].toInt() and 255).toFloat()
            val delta = value - mean[i]
            mean[i] += delta / calibrationCount
            m2[i] += delta * (value - mean[i])
        }
        val duration = timestampNs - calibrationStart
        if (duration >= 2_000_000_000L && calibrationCount >= 30) {
            var meanVariance = 0.0
            var clipped = 0
            var n = 0
            for (y in roi.y until roi.y + roi.height step 3) {
                for (x in roi.x until roi.x + roi.width step 3) {
                    val i = y * width + x
                    meanVariance += m2[i] / max(1, calibrationCount - 1)
                    if (mean[i] < 3 || mean[i] > 252) clipped++
                    n++
                }
            }
            if (meanVariance / max(1, n) > 25.0 || clipped > n / 4) {
                calibrationCount = 0
                calibrationStart = 0L
                return result("Завеликий шум або пересвічення. Покращте освітлення", "Еталон відхилено")
            }
            for (i in variance.indices) variance[i] = max(0f, m2[i] / max(1, calibrationCount - 1))
            buildReferenceImages()
            ready = true
            requested = false
            clearHistory()
            return result("Еталон готовий. Тепер можна ввімкнути потік", "Калібрування завершено")
        }
        return result("Калібрування: ${calibrationCount} кадрів; потік має бути вимкнений", "Сцена стабільна", registration = reg)
    }

    private fun buildReferenceImages() {
        reference32.create(height, width, CvType.CV_32FC1)
        reference32.put(0, 0, mean)
        reference32.convertTo(reference8, CvType.CV_8UC1)
        Imgproc.resize(reference8, smallReference, Size(smallWidth.toDouble(), smallHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        detectFeatures()
    }

    private fun detectFeatures() {
        if (smallReference.empty() || controlMask.empty()) return
        Imgproc.goodFeaturesToTrack(smallReference, corners, 120, .015, 8.0, controlMask, 5, false, .04)
        controlPoints.fromArray(*corners.toArray())
        if (!reference8.empty()) {
            Imgproc.goodFeaturesToTrack(reference8, corners, 140, .02, 18.0, localMask, 5, false, .04)
            localPoints.fromArray(*corners.toArray())
        }
    }

    private fun register(): Registration {
        if (controlPoints.total() < 12 || smallReference.empty()) return Registration()
        val tracks = track(smallReference, smallFrame, controlPoints, .6, 25.0)
        if (tracks.size < 10) return Registration(count = tracks.size)
        val xs = tracks.map { it[2] - it[0] }.toDoubleArray()
        val ys = tracks.map { it[3] - it[1] }.toDoubleArray()
        val mx = FlowMath.median(xs)
        val my = FlowMath.median(ys)
        val inliers = tracks.filter { hypot(it[2] - it[0] - mx, it[3] - it[1] - my) < .55 }
        if (inliers.size < 10 || inliers.size < tracks.size * .65) return Registration(count = inliers.size)
        // Requiring several image quadrants prevents a single small moving object becoming the reference.
        val quadrants = inliers.map { (if (it[0] >= smallWidth / 2.0) 1 else 0) + (if (it[1] >= smallHeight / 2.0) 2 else 0) }.toSet()
        if (quadrants.size < 3) return Registration(count = inliers.size)
        val x = FlowMath.median(inliers.map { it[2] - it[0] }.toDoubleArray())
        val y = FlowMath.median(inliers.map { it[3] - it[1] }.toDoubleArray())
        val scatter = sqrt(inliers.sumOf { val dx = it[2] - it[0] - x; val dy = it[3] - it[1] - y; dx * dx + dy * dy } / inliers.size)
        return Registration(x * scaleX, y * scaleY, scatter < .4, inliers.size, scatter)
    }

    private fun localDeformation(threshold: Double): List<FlowVector> {
        if (localPoints.total() < 4) return emptyList()
        return track(reference8, aligned, localPoints, .65, 18.0).mapNotNull { p ->
            val dx = p[2] - p[0]
            val dy = p[3] - p[1]
            val amplitude = hypot(dx, dy)
            val noise = localDisplacementNoise(p[0].toInt(), p[1].toInt())
            if (amplitude <= max(.03, noise * threshold) || amplitude > 12 || p[2] < roi.x + 4 || p[2] >= roi.x + roi.width - 4 ||
                p[3] < roi.y + 4 || p[3] >= roi.y + roi.height - 4) null
            else FlowVector(p[0].toFloat(), p[1].toFloat(), dx.toFloat(), dy.toFloat(),
                (1.0 / (1.0 + p[4] * 2.0 + p[5] / 8.0)).toFloat())
        }
    }

    /** Conservative local observability estimate. Correlated samples are not counted
     * as independent: no artificial sqrt(window-size) improvement is claimed. */
    private fun localDisplacementNoise(cx: Int, cy: Int): Double {
        var xx = 0.0; var yy = 0.0; var xy = 0.0; var noise = 0.0; var n = 0
        for (y in max(1, cy - 10)..min(height - 2, cy + 10)) {
            for (x in max(1, cx - 10)..min(width - 2, cx + 10)) {
                val i = y * width + x
                val gx = (mean[i + 1] - mean[i - 1]) * .5
                val gy = (mean[i + width] - mean[i - width]) * .5
                xx += gx * gx; yy += gy * gy; xy += gx * gy
                noise += variance[i]; n++
            }
        }
        if (n == 0) return Double.POSITIVE_INFINITY
        val eigenvalue = ((xx + yy) - sqrt((xx - yy) * (xx - yy) + 4 * xy * xy)) / (2 * n)
        return sqrt((2 * noise / n + .25) / max(eigenvalue, .01))
    }

    /** Entries contain x0,y0,x1,y1,forward-backward distance,photometric LK error. */
    private fun track(a: Mat, b: Mat, points: MatOfPoint2f, fbLimit: Double, errorLimit: Double): List<DoubleArray> {
        Video.calcOpticalFlowPyrLK(a, b, points, tracked, forwardStatus, forwardError,
            Size(21.0, 21.0), 2, termination, 0, 1e-4)
        Video.calcOpticalFlowPyrLK(b, a, tracked, returned, backwardStatus, backwardError,
            Size(21.0, 21.0), 2, termination, 0, 1e-4)
        val src = points.toArray()
        val dst = tracked.toArray()
        val back = returned.toArray()
        val ok = forwardStatus.toArray()
        val backOk = backwardStatus.toArray()
        val err = forwardError.toArray()
        val result = ArrayList<DoubleArray>(src.size)
        for (i in src.indices) {
            if (ok[i].toInt() == 0 || backOk[i].toInt() == 0 || !err[i].isFinite() || err[i] > errorLimit) continue
            if (!dst[i].x.isFinite() || !dst[i].y.isFinite() || dst[i].x < 2 || dst[i].y < 2 || dst[i].x >= b.cols() - 2 || dst[i].y >= b.rows() - 2) continue
            val fb = hypot(back[i].x - src[i].x, back[i].y - src[i].y)
            if (fb.isFinite() && fb <= fbLimit) result.add(doubleArrayOf(src[i].x, src[i].y, dst[i].x, dst[i].y, fb, err[i].toDouble()))
        }
        return result
    }

    private fun controlStatistics(values: ByteArray, reference: FloatArray): Pair<Double, Double> {
        var sum = 0.0
        var squares = 0.0
        var n = 0
        val margin = 12
        for (y in margin until height - margin step 6) {
            for (x in margin until width - margin step 6) {
                if (x >= roi.x - 8 && x < roi.x + roi.width + 8 && y >= roi.y - 8 && y < roi.y + roi.height + 8) continue
                val i = y * width + x
                val d = (values[i].toInt() and 255) - reference[i]
                sum += d; squares += d * d; n++
            }
        }
        if (n == 0) return 0.0 to 255.0
        val average = sum / n
        return average to sqrt(max(0.0, squares / n - average * average))
    }

    private fun nearestHistory(target: Long): HistoryFrame? =
        history.filter { it.timestamp <= target }.maxByOrNull { it.timestamp }

    private fun remember(timestamp: Long, offset: Double) {
        // Limit history to about 30 Hz and 36 ROI-sized frames, regardless of camera FPS.
        if (lastHistoryTimestamp != Long.MIN_VALUE && timestamp - lastHistoryTimestamp < 33_000_000L) return
        val size = roi.width * roi.height
        val item = if (history.size < 36) HistoryFrame(timestamp, ByteArray(size), offset).also { history.add(it) }
        else history[historyCursor].also { historyCursor = (historyCursor + 1) % history.size }
        item.timestamp = timestamp; item.offset = offset
        var k = 0
        for (y in roi.y until roi.y + roi.height) {
            val start = y * width + roi.x
            bytes.copyInto(item.pixels, k, start, start + roi.width)
            k += roi.width
        }
        lastHistoryTimestamp = timestamp
    }

    private fun clearHistory() {
        history.clear(); historyCursor = 0; lastHistoryTimestamp = Long.MIN_VALUE
    }

    private fun ensureSize(w: Int, h: Int) {
        if (w == width && h == height) return
        val wasRequested = requested
        resetReference()
        requested = wasRequested
        width = w; height = h
        smallWidth = min(w, 640)
        smallHeight = max(8, (h.toDouble() * smallWidth / w).toInt())
        scaleX = w.toDouble() / smallWidth; scaleY = h.toDouble() / smallHeight
        bytes = ByteArray(w * h); rgba = ByteArray(w * h * 4)
        mean = FloatArray(w * h); variance = FloatArray(w * h); m2 = FloatArray(w * h)
        initial = ByteArray(w * h)
        overlay.create(h, w, CvType.CV_8UC4)
        rebuildMasks()
    }

    private fun rebuildMasks() {
        if (width == 0 || height == 0) return
        val l = (currentRegion.left * width).toInt().coerceIn(1, width - 3)
        val t = (currentRegion.top * height).toInt().coerceIn(1, height - 3)
        val r = (currentRegion.right * width).toInt().coerceIn(l + 2, width - 1)
        val b = (currentRegion.bottom * height).toInt().coerceIn(t + 2, height - 1)
        roi = Rect(l, t, r - l, b - t)
        controlMask.create(smallHeight, smallWidth, CvType.CV_8UC1)
        controlMask.setTo(Scalar.all(255.0))
        Imgproc.rectangle(controlMask, Point(l / scaleX - 5, t / scaleY - 5), Point(r / scaleX + 5, b / scaleY + 5), Scalar.all(0.0), -1)
        Imgproc.rectangle(controlMask, Point(0.0, 0.0), Point((smallWidth - 1).toDouble(), (smallHeight - 1).toDouble()), Scalar.all(0.0), 12)
        localMask.create(height, width, CvType.CV_8UC1)
        localMask.setTo(Scalar.all(0.0))
        Imgproc.rectangle(localMask, Point(l.toDouble() + 4, t.toDouble() + 4), Point(r.toDouble() - 4, b.toDouble() - 4), Scalar.all(255.0), -1)
    }

    private fun resetReference() {
        ready = false; calibrationCount = 0; calibrationStart = 0L
        lastTimestamp = Long.MIN_VALUE
        clearHistory()
        controlPoints.release(); localPoints.release()
    }

    private fun result(
        message: String, quality: String, vectors: List<FlowVector> = emptyList(),
        signal: Double = 0.0, registration: Registration = Registration()
    ) = ProcessingResult(overlay, vectors, message, quality, ready,
        if (ready) 1f else if (calibrationCount == 0) 0f else min(calibrationCount / 30.0,
            ((lastTimestamp - calibrationStart).coerceAtLeast(0L) / 2_000_000_000.0)).coerceIn(0.0, .99).toFloat(),
        signal, registration.x, registration.y)

    @Synchronized override fun close() {
        if (closed) return
        stop()
        listOf(frame, aligned, reference8, reference32, overlay, smallFrame, smallReference,
            controlMask, localMask, transform, corners, controlPoints, localPoints, tracked, returned,
            forwardStatus, backwardStatus, forwardError, backwardError).forEach { it.release() }
        bytes = ByteArray(0); rgba = ByteArray(0); mean = FloatArray(0); variance = FloatArray(0)
        m2 = FloatArray(0); initial = ByteArray(0)
        closed = true
    }

    companion object {
        fun sanitizeRegion(r: NormalizedRegion): NormalizedRegion {
            if (!r.left.isFinite() || !r.top.isFinite() || !r.right.isFinite() || !r.bottom.isFinite()) return NormalizedRegion()
            val l = min(r.left, r.right).coerceIn(.05f, .75f)
            val t = min(r.top, r.bottom).coerceIn(.05f, .75f)
            return NormalizedRegion(l, t, max(r.left, r.right).coerceIn(l + .20f, .95f), max(r.top, r.bottom).coerceIn(t + .20f, .95f))
        }
    }
}
