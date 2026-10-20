package org.sappari.air_visualizer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PlanePackingTest {
    @Test fun paddedPlaneWithNonzeroPositionPreservesBufferAndOtherChannel() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 10, 88, 11, 88, 88, 20, 88, 21))
        source.position(1)
        val output = ByteArray(8) { 7 }
        PlanePacking.copy(source, 2, 2, 5, 2, output, 1, 2)
        assertArrayEquals(byteArrayOf(7, 10, 7, 11, 7, 20, 7, 21), output)
        assertEquals(1, source.position())
    }
    @Test fun directReadOnlyPlaneWorks() {
        val source = ByteBuffer.allocateDirect(4).put(byteArrayOf(1, 2, 3, 4))
        source.flip()
        val output = ByteArray(4)
        PlanePacking.copy(source.asReadOnlyBuffer(), 2, 2, 2, 1, output, 0, 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output)
    }
    @Test(expected = IllegalArgumentException::class)
    fun truncatedPlaneRejected() {
        PlanePacking.copy(ByteBuffer.allocate(3), 2, 2, 2, 1, ByteArray(4), 0, 1)
    }
    @Test(expected = IllegalArgumentException::class)
    fun destinationOverflowRejected() {
        PlanePacking.copy(ByteBuffer.allocate(4), 2, 2, 2, 1, ByteArray(4), 1, 1)
    }
    @Test(expected = IllegalArgumentException::class)
    fun arithmeticOverflowRejectedBeforeAnyRead() {
        PlanePacking.copy(ByteBuffer.allocate(4), Int.MAX_VALUE, 2, 2, Int.MAX_VALUE, ByteArray(4), 0, 1)
    }
}
