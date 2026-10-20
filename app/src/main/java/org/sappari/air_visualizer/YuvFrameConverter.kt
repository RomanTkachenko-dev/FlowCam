package org.sappari.air_visualizer

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Reuses native buffers; gray and rgba are borrowed until the next convert call. */
class YuvFrameConverter : AutoCloseable {
    private var bytes = ByteArray(0)
    private val yuv = Mat()
    private val color = Mat()
    private val rotated = Mat()
    private val rotatedY = Mat()
    val gray = Mat()
    val rgba = Mat()

    fun convert(image: ImageProxy) {
        val width = image.width
        val height = image.height
        require(width % 2 == 0 && height % 2 == 0)
        val required = width * height * 3 / 2
        if (bytes.size != required) bytes = ByteArray(required)
        val y = image.planes[0]
        PlanePacking.copy(y.buffer, width, height, y.rowStride, y.pixelStride, bytes, 0, 1)
        val u = image.planes[1]; val v = image.planes[2]
        PlanePacking.copy(v.buffer, width / 2, height / 2, v.rowStride, v.pixelStride, bytes, width * height, 2)
        PlanePacking.copy(u.buffer, width / 2, height / 2, u.rowStride, u.pixelStride, bytes, width * height + 1, 2)
        yuv.create(height + height / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, bytes)
        Imgproc.cvtColor(yuv, color, Imgproc.COLOR_YUV2RGBA_NV21)
        val crop = image.cropRect
        val roi = color.submat(Rect(crop.left, crop.top, crop.width(), crop.height()))
        try {
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(roi, rotated, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(roi, rotated, Core.ROTATE_180)
                270 -> Core.rotate(roi, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> roi.copyTo(rotated)
            }
        } finally { roi.release() }
        val ratio = minOf(1.0, 960.0 / maxOf(rotated.cols(), rotated.rows()))
        val w = maxOf(2, (rotated.cols() * ratio).toInt() / 2 * 2)
        val h = maxOf(2, (rotated.rows() * ratio).toInt() / 2 * 2)
        Imgproc.resize(rotated, rgba, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        // Measurement uses the camera's original Y samples, not reconstructed RGB.
        val yView = yuv.submat(0, height, 0, width)
        val yCrop = yView.submat(Rect(crop.left, crop.top, crop.width(), crop.height()))
        try {
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(yCrop, rotatedY, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(yCrop, rotatedY, Core.ROTATE_180)
                270 -> Core.rotate(yCrop, rotatedY, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> yCrop.copyTo(rotatedY)
            }
            Imgproc.resize(rotatedY, gray, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } finally { yCrop.release(); yView.release() }
    }

    override fun close() { yuv.release(); color.release(); rotated.release(); rotatedY.release(); gray.release(); rgba.release() }
}
