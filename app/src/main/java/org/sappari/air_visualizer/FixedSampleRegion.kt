package org.sappari.air_visualizer

import kotlin.math.ceil
import kotlin.math.floor

/** A fixed, half-open crop in camera-frame pixels; later detections do not move it. */
data class FixedSampleRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val frameWidth: Int,
    val frameHeight: Int
) {
    init {
        require(frameWidth > 0 && frameHeight > 0) { "Invalid frame dimensions" }
        require(x >= 0 && y >= 0 && width >= 2 && height >= 2) { "Invalid sample region" }
        require(x.toLong() + width <= frameWidth && y.toLong() + height <= frameHeight) {
            "Sample region exceeds the frame"
        }
    }

    fun matchesFrame(width: Int, height: Int): Boolean =
        width == frameWidth && height == frameHeight

    companion object {
        fun fromPoints(
            points: List<Pair<Double, Double>>,
            frameWidth: Int,
            frameHeight: Int
        ): FixedSampleRegion {
            require(frameWidth > 0 && frameHeight > 0) { "Invalid frame dimensions" }
            require(points.size == 4) { "Exactly four outer corners are required" }
            require(points.all { (x, y) -> x.isFinite() && y.isFinite() }) {
                "Corner coordinates must be finite"
            }

            // Clamp while still Double so even extreme finite coordinates cannot overflow Int.
            val left = floor(points.minOf { it.first }).coerceIn(0.0, frameWidth.toDouble()).toInt()
            val top = floor(points.minOf { it.second }).coerceIn(0.0, frameHeight.toDouble()).toInt()
            val right = ceil(points.maxOf { it.first }).coerceIn(0.0, frameWidth.toDouble()).toInt()
            val bottom = ceil(points.maxOf { it.second }).coerceIn(0.0, frameHeight.toDouble()).toInt()

            return FixedSampleRegion(left, top, right - left, bottom - top, frameWidth, frameHeight)
        }
    }
}
