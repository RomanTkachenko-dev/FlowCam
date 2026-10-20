package org.sappari.air_visualizer

import org.junit.Assert.*
import org.junit.Test

class FixedSampleRegionTest {
    private fun rectangle(left: Double, top: Double, right: Double, bottom: Double) = listOf(
        left to top, right to top, right to bottom, left to bottom
    )

    @Test fun roundsFractionalCornersOutward() {
        val result = FixedSampleRegion.fromPoints(rectangle(10.7, 20.2, 40.1, 60.8), 100, 80)
        assertEquals(FixedSampleRegion(10, 20, 31, 41, 100, 80), result)
    }

    @Test fun integerBoundariesStayHalfOpenWithoutAnExtraPixel() {
        val result = FixedSampleRegion.fromPoints(rectangle(10.0, 20.0, 40.0, 60.0), 100, 80)
        assertEquals(FixedSampleRegion(10, 20, 30, 40, 100, 80), result)
    }

    @Test fun handlesUnorderedPerspectiveCornersByTheirExtrema() {
        val points = listOf(49.2 to 61.7, 9.8 to 29.3, 15.4 to 65.1, 45.6 to 20.2)
        val result = FixedSampleRegion.fromPoints(points, 100, 80)
        assertEquals(FixedSampleRegion(9, 20, 41, 46, 100, 80), result)
    }

    @Test fun clipsPartiallyVisibleCornersAtEveryFrameEdge() {
        val result = FixedSampleRegion.fromPoints(rectangle(-5.2, -8.9, 110.1, 90.3), 100, 80)
        assertEquals(FixedSampleRegion(0, 0, 100, 80, 100, 80), result)
    }

    @Test fun acceptsRegionEndingExactlyAtTheFrameBoundary() {
        val result = FixedSampleRegion.fromPoints(rectangle(98.0, 78.0, 100.0, 80.0), 100, 80)
        assertEquals(FixedSampleRegion(98, 78, 2, 2, 100, 80), result)
    }

    @Test fun clampsExtremeFiniteCoordinatesBeforeIntegerConversion() {
        val points = rectangle(-Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(FixedSampleRegion(0, 0, 100, 80, 100, 80),
            FixedSampleRegion.fromPoints(points, 100, 80))
    }

    @Test fun rejectsPointsEntirelyOutsideAnyOneSideOfTheFrame() {
        val outside = listOf(
            rectangle(-20.0, 10.0, -1.0, 30.0),
            rectangle(100.0, 10.0, 120.0, 30.0),
            rectangle(10.0, -20.0, 30.0, -1.0),
            rectangle(10.0, 80.0, 30.0, 100.0)
        )
        outside.forEach { points ->
            assertThrows(IllegalArgumentException::class.java) {
                FixedSampleRegion.fromPoints(points, 100, 80)
            }
        }
    }

    @Test fun rejectsClippedRegionsSmallerThanTwoPixelsInEitherDimension() {
        val tooSmall = listOf(
            rectangle(3.2, 10.0, 3.8, 30.0),
            rectangle(10.0, 3.2, 30.0, 3.8),
            rectangle(99.2, 10.0, 120.0, 30.0),
            rectangle(10.0, 79.2, 30.0, 100.0),
            rectangle(10.0, 10.0, 10.0, 10.0)
        )
        tooSmall.forEach { points ->
            assertThrows(IllegalArgumentException::class.java) {
                FixedSampleRegion.fromPoints(points, 100, 80)
            }
        }
    }

    @Test fun rejectsEveryNonFiniteCoordinate() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            for (coordinate in 0..1) {
                val points = rectangle(10.0, 10.0, 30.0, 30.0).toMutableList()
                points[2] = if (coordinate == 0) invalid to 30.0 else 30.0 to invalid
                assertThrows(IllegalArgumentException::class.java) {
                    FixedSampleRegion.fromPoints(points, 100, 80)
                }
            }
        }
    }

    @Test fun requiresExactlyFourPoints() {
        val points = rectangle(10.0, 10.0, 30.0, 30.0)
        listOf(emptyList(), points.take(3), points + (20.0 to 20.0)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                FixedSampleRegion.fromPoints(invalid, 100, 80)
            }
        }
    }

    @Test fun rejectsNonPositiveFrameDimensions() {
        listOf(0 to 80, 100 to 0, -1 to 80, 100 to -1).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) {
                FixedSampleRegion.fromPoints(rectangle(10.0, 10.0, 30.0, 30.0), width, height)
            }
        }
    }

    @Test fun laterCornerMovementCannotChangeTheSavedRegion() {
        val points = rectangle(10.0, 20.0, 40.0, 60.0).toMutableList()
        val saved = FixedSampleRegion.fromPoints(points, 100, 80)
        points.indices.forEach { index ->
            points[index] = points[index].first + 3.0 to points[index].second + 4.0
        }
        val later = FixedSampleRegion.fromPoints(points, 100, 80)
        assertEquals(FixedSampleRegion(10, 20, 30, 40, 100, 80), saved)
        assertEquals(FixedSampleRegion(13, 24, 30, 40, 100, 80), later)
    }

    @Test fun matchesOnlyTheOriginalFrameDimensions() {
        val saved = FixedSampleRegion.fromPoints(rectangle(10.0, 20.0, 40.0, 60.0), 100, 80)
        assertTrue(saved.matchesFrame(100, 80))
        assertFalse(saved.matchesFrame(101, 80))
        assertFalse(saved.matchesFrame(100, 81))
        assertFalse(saved.matchesFrame(80, 100))
        assertFalse(saved.matchesFrame(0, 0))
    }

    @Test fun constructorRejectsInvalidOrOverflowingBounds() {
        listOf(
            listOf(-1, 0, 2, 2, 100, 80),
            listOf(0, -1, 2, 2, 100, 80),
            listOf(0, 0, 1, 2, 100, 80),
            listOf(0, 0, 2, 1, 100, 80),
            listOf(99, 0, 2, 2, 100, 80),
            listOf(0, 79, 2, 2, 100, 80),
            listOf(0, 0, 2, 2, 0, 80),
            listOf(Int.MAX_VALUE - 1, 0, 3, 2, Int.MAX_VALUE, 80)
        ).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) {
                FixedSampleRegion(values[0], values[1], values[2], values[3], values[4], values[5])
            }
        }
    }
}
