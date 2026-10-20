package org.sappari.air_visualizer

import org.junit.Assert.*
import org.junit.Test

class ArgbYuv420Test {
    private fun convert(pixels: IntArray, width: Int = 2, height: Int = 2, semiPlanar: Boolean = false): IntArray {
        val bytes = ByteArray(width * height * 3 / 2)
        ArgbYuv420.convert(pixels, width, height, bytes, semiPlanar)
        return bytes.map { it.toInt() and 255 }.toIntArray()
    }

    @Test fun blackAndWhiteUseLimitedRangeAndNeutralChroma() {
        assertArrayEquals(intArrayOf(16, 16, 16, 16, 128, 128), convert(IntArray(4) { 0xff000000.toInt() }))
        assertArrayEquals(intArrayOf(235, 235, 235, 235, 128, 128), convert(IntArray(4) { 0xffffffff.toInt() }))
    }

    @Test fun primaryColoursHaveCorrectChromaOrdering() {
        assertArrayEquals(intArrayOf(82, 82, 82, 82, 90, 240), convert(IntArray(4) { 0xffff0000.toInt() }))
        assertArrayEquals(intArrayOf(41, 41, 41, 41, 240, 110), convert(IntArray(4) { 0xff0000ff.toInt() }))
    }

    @Test fun chromaAveragesTheWholeBlockInsteadOfTakingOnlyItsFirstPixel() {
        val result = convert(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt()))
        assertArrayEquals(intArrayOf(82, 144, 41, 235, 128, 128), result)
    }

    @Test fun planarAndSemiPlanarLayoutsAreDifferentForMultipleChromaSamples() {
        val red = 0xffff0000.toInt()
        val blue = 0xff0000ff.toInt()
        val pixels = intArrayOf(red, red, blue, blue, red, red, blue, blue)
        val planar = convert(pixels, 4, 2, false)
        val semi = convert(pixels, 4, 2, true)
        assertArrayEquals(intArrayOf(90, 240, 240, 110), planar.copyOfRange(8, 12))
        assertArrayEquals(intArrayOf(90, 240, 240, 110), semi.copyOfRange(8, 12))
        // Red and blue happen to share V(red) == U(blue); use green to disambiguate layout.
        val redGreen = intArrayOf(red, red, 0xff00ff00.toInt(), 0xff00ff00.toInt(),
            red, red, 0xff00ff00.toInt(), 0xff00ff00.toInt())
        val planarGreen = convert(redGreen, 4, 2, false)
        val semiGreen = convert(redGreen, 4, 2, true)
        assertArrayEquals(intArrayOf(90, 54, 240, 34), planarGreen.copyOfRange(8, 12))
        assertArrayEquals(intArrayOf(90, 240, 54, 34), semiGreen.copyOfRange(8, 12))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOddWidth() {
        ArgbYuv420.convert(IntArray(6), 3, 2, ByteArray(9), false)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsShortDestination() {
        ArgbYuv420.convert(IntArray(4), 2, 2, ByteArray(5), false)
    }
}
