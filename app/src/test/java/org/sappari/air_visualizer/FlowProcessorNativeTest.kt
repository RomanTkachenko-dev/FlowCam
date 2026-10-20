package org.sappari.air_visualizer

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File

/** Runs the actual production Kotlin processor against desktop OpenCV 4.12 JNI.
 * This tests image arithmetic, not Camera2, Android rendering, or gas velocities. */
class FlowProcessorNativeTest {
    companion object {
        @JvmStatic @BeforeClass fun loadNative() {
            val dll = System.getProperty("flow.opencv.dll")
            assumeTrue("Set FLOW_OPENCV_DLL to an OpenCV 4.12 desktop JNI library", dll != null && File(dll).isFile)
            System.load(requireNotNull(dll))
            assertTrue(Core.VERSION.startsWith("4.12."))
        }
    }
    private val width = 320
    private val height = 240
    private val options = ProcessingOptions(region = NormalizedRegion(.25f, .25f, .75f, .75f), denoise = false)

    private fun texture(): Mat {
        val random = java.util.Random(123456)
        val bytes = ByteArray(width * height)
        for (y in 0 until height step 5) for (x in 0 until width step 5) {
            val value = (30 + random.nextInt(196)).toByte()
            for (dy in 0 until minOf(5, height - y)) for (dx in 0 until minOf(5, width - x))
                bytes[(y + dy) * width + x + dx] = value
        }
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        Imgproc.GaussianBlur(mat, mat, Size(3.0, 3.0), .7)
        return mat
    }
    private fun calibrated(engine: FlowProcessor, scene: Mat): CalibrationData {
        engine.beginCalibration()
        var result: ProcessingResult? = null
        repeat(65) { result = engine.process(scene, (it + 1) * 40_000_000L, options) }
        assertTrue(result!!.message, result!!.calibrated)
        return requireNotNull(engine.exportCalibration())
    }

    @Test fun staticSceneHasNoHeatOrArrowsAfterCalibration() {
        val scene = texture()
        FlowProcessor().use { engine ->
            calibrated(engine, scene)
            val result = engine.process(scene, 3_000_000_000, options.copy(mode = AnalysisMode.DEFORMATION))
            assertEquals(0.0, result.meanSignal, .01)
            assertTrue(result.vectors.toString(), result.vectors.isEmpty())
        }
        scene.release()
    }
    @Test fun uniformSceneCannotCalibrate() {
        val scene = Mat(height, width, CvType.CV_8UC1, Scalar(128.0))
        FlowProcessor().use { engine ->
            engine.beginCalibration()
            repeat(70) { assertFalse(engine.process(scene, it * 40_000_000L, options).calibrated) }
            assertNull(engine.exportCalibration())
        }
        scene.release()
    }
    @Test fun wholeFrameTranslationIsRemovedUsingOutsideRegion() {
        val scene = texture()
        val moved = Mat()
        val transform = Mat(2, 3, CvType.CV_64F)
        transform.put(0, 0, 1.0, 0.0, 2.0, 0.0, 1.0, -1.0)
        Imgproc.warpAffine(scene, moved, transform, scene.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        FlowProcessor().use { engine ->
            calibrated(engine, scene)
            val result = engine.process(moved, 3_000_000_000, options)
            assertEquals(result.message, 2.0, result.shiftX, .15)
            assertEquals(-1.0, result.shiftY, .15)
            assertTrue("Residual ${result.meanSignal}", result.meanSignal < .5)
            assertFalse(result.quality.contains("призупинене"))
        }
        transform.release(); moved.release(); scene.release()
    }
    @Test fun localizedSyntheticWarpGivesCorrectImageDisplacement() {
        val scene = texture()
        val moved = scene.clone()
        val transform = Mat(2, 3, CvType.CV_64F)
        transform.put(0, 0, 1.0, 0.0, 1.5, 0.0, 1.0, -.75)
        val allMoved = Mat()
        Imgproc.warpAffine(scene, allMoved, transform, scene.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        val roi = Rect(95, 75, 130, 90)
        val src = allMoved.submat(roi); val dst = moved.submat(roi)
        src.copyTo(dst); src.release(); dst.release()
        FlowProcessor().use { engine ->
            calibrated(engine, scene)
            val result = engine.process(moved, 3_000_000_000, options.copy(mode = AnalysisMode.DEFORMATION))
            val vectors = result.vectors.filter { it.x in 110f..205f && it.y in 90f..145f }
            assertTrue("Only ${vectors.size} vectors: ${result.message}", vectors.size >= 5)
            assertEquals(1.5, FlowMath.median(vectors.map { it.dx.toDouble() }.toDoubleArray()), .22)
            assertEquals(-.75, FlowMath.median(vectors.map { it.dy.toDouble() }.toDoubleArray()), .22)
        }
        transform.release(); allMoved.release(); moved.release(); scene.release()
    }
    @Test fun largeLightingChangeSuspendsOutput() {
        val scene = texture(); val brighter = Mat()
        Core.add(scene, Scalar(20.0), brighter)
        FlowProcessor().use { engine ->
            calibrated(engine, scene)
            val result = engine.process(brighter, 3_000_000_000, options.copy(mode = AnalysisMode.DEFORMATION))
            assertTrue(result.message, result.quality.contains("призупинене"))
            assertTrue(result.vectors.isEmpty()); assertEquals(0.0, result.meanSignal, 0.0)
        }
        brighter.release(); scene.release()
    }
    @Test fun sparseRecordingUsesActualIntervalRatherThanInventingFrames() {
        val scene = texture()
        FlowProcessor().use { engine ->
            val data = calibrated(engine, scene)
            engine.importCalibration(data)
            val temporal = options.copy(mode = AnalysisMode.TEMPORAL, lagMs = 20)
            val first = engine.process(scene, 3_000_000_000, temporal)
            assertTrue(first.message, first.message.contains("Накопичення"))
            val next = engine.process(scene, 3_125_000_000, temporal)
            assertTrue(next.message, next.message.contains("125 мс"))
            assertEquals(0.0, next.meanSignal, .01)
        }
        scene.release()
    }
    @Test fun importedReferenceReproducesMeasurement() {
        val scene = texture(); val changed = scene.clone()
        val patch = changed.submat(Rect(130, 100, 35, 35))
        Core.add(patch, Scalar(3.0), patch); patch.release()
        FlowProcessor().use { first ->
            val data = calibrated(first, scene)
            val expected = first.process(changed, 3_000_000_000, options).meanSignal
            FlowProcessor().use { second ->
                second.importCalibration(data)
                val actual = second.process(changed, 3_000_000_000, options).meanSignal
                assertEquals(expected, actual, 1e-6)
                assertTrue(expected > 0)
            }
        }
        changed.release(); scene.release()
    }
    @Test fun newFilteringInvalidatesReference() {
        val scene = texture()
        FlowProcessor().use { engine ->
            calibrated(engine, scene)
            assertFalse(engine.process(scene, 3_000_000_000, options.copy(denoise = true)).calibrated)
            assertNull(engine.exportCalibration())
        }
        scene.release()
    }
}
