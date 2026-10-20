package org.sappari.air_visualizer

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class FlowMathTest {
    @Test fun noiseThresholdSuppressesFluctuationsButKeepsSign() {
        assertEquals(0.0, FlowMath.thresholdResidual(2.0, 4.0, 2.0), 1e-9)
        val expected = 10.0 - 2.0 * sqrt(8.25)
        assertEquals(expected, FlowMath.thresholdResidual(10.0, 4.0, 2.0), 1e-9)
        assertEquals(-expected, FlowMath.thresholdResidual(-10.0, 4.0, 2.0), 1e-9)
    }
    @Test fun disablingThresholdRetainsSignedResidual() {
        assertEquals(-.25, FlowMath.thresholdResidual(-.25, 100.0, 0.0), 1e-9)
    }
    @Test fun zeroMeasuredVarianceStillHasQuantizationFloor() {
        assertEquals(0.0, FlowMath.thresholdResidual(.9, 0.0, 2.0), 1e-9)
        assertEquals(1.0, FlowMath.thresholdResidual(2.0, 0.0, 2.0), 1e-9)
    }
    @Test fun medianRejectsAnOutlierAndDoesNotModifyInput() {
        val input = doubleArrayOf(100.0, 1.0, 1.1, .9, 1.2)
        assertEquals(1.1, FlowMath.median(input), 1e-9)
        assertEquals(100.0, input[0], 1e-9)
        assertEquals(2.0, FlowMath.median(doubleArrayOf(1.0, 3.0)), 1e-9)
    }
}
