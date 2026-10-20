package org.sappari.air_visualizer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Pure numerical helpers, independent of OpenCV and Android. */
internal object FlowMath {
    fun median(values: DoubleArray): Double {
        require(values.isNotEmpty() && values.all { it.isFinite() })
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    /** Conservative two-frame noise model; a 0.5-level floor avoids division by zero. */
    fun thresholdResidual(residual: Double, singleFrameVariance: Double, threshold: Double): Double {
        require(residual.isFinite() && singleFrameVariance.isFinite() && threshold.isFinite())
        val floor = max(0.0, threshold) * sqrt(2.0 * max(0.0, singleFrameVariance) + .25)
        val amplitude = max(0.0, abs(residual) - floor)
        return if (residual < 0) -amplitude else amplitude
    }
}
