package org.sappari.air_visualizer

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LumaPlaneReaderTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun packedRowsPreserveFullUnsignedRange() {
        val expected = bytes(0, 127, 128, 255, 31, 92)
        assertArrayEquals(expected, LumaPlaneReader.read(ByteBuffer.wrap(expected), 3, 2, 3, 1))
    }

    @Test fun paddedRowsDoNotRequirePaddingAfterLastRow() {
        val source = ByteBuffer.wrap(bytes(10, 11, 12, 99, 99, 20, 21, 22))
        assertArrayEquals(bytes(10, 11, 12, 20, 21, 22), LumaPlaneReader.read(source, 3, 2, 5, 1))
    }

    @Test fun bufferPositionIsTheFirstPixelAndIsNotChanged() {
        val source = ByteBuffer.wrap(bytes(77, 77, 77, 10, 11, 12, 99, 99, 20, 21, 22, 77))
        source.position(3)
        source.limit(11)
        assertArrayEquals(bytes(10, 11, 12, 20, 21, 22), LumaPlaneReader.read(source, 3, 2, 5, 1))
        assertEquals(3, source.position())
        assertEquals(11, source.limit())
    }

    @Test fun interleavedPixelsAndRowPaddingAreSkipped() {
        val source = ByteBuffer.wrap(bytes(10, 99, 11, 99, 12, 99, 88, 20, 99, 21, 99, 22))
        assertArrayEquals(bytes(10, 11, 12, 20, 21, 22), LumaPlaneReader.read(source, 3, 2, 7, 2))
    }

    @Test fun directReadOnlyPlaneWorks() {
        val direct = ByteBuffer.allocateDirect(8)
        direct.put(bytes(10, 11, 12, 99, 99, 20, 21, 22)).flip()
        assertArrayEquals(bytes(10, 11, 12, 20, 21, 22), LumaPlaneReader.read(direct.asReadOnlyBuffer(), 3, 2, 5, 1))
    }

    @Test fun aSlicedPlaneWorksWithoutReadingItsPrefix() {
        val parent = ByteBuffer.wrap(bytes(99, 99, 10, 11, 12, 20, 21, 22, 99))
        parent.position(2)
        parent.limit(8)
        assertArrayEquals(bytes(10, 11, 12, 20, 21, 22), LumaPlaneReader.read(parent.slice(), 3, 2, 3, 1))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMissingLastPixel() {
        LumaPlaneReader.read(ByteBuffer.allocate(7), 3, 2, 5, 1)
    }

    @Test(expected = IllegalArgumentException::class) fun respectsLimitRatherThanCapacity() {
        val source = ByteBuffer.allocate(100)
        source.limit(5)
        LumaPlaneReader.read(source, 3, 2, 3, 1)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOverlappingRows() {
        LumaPlaneReader.read(ByteBuffer.allocate(100), 3, 2, 4, 2)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyImage() {
        LumaPlaneReader.read(ByteBuffer.allocate(1), 0, 1, 1, 1)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsZeroStride() {
        LumaPlaneReader.read(ByteBuffer.allocate(10), 1, 1, 1, 0)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOverflowingAllocationBeforeReading() {
        LumaPlaneReader.read(ByteBuffer.allocate(1), Int.MAX_VALUE, 2, Int.MAX_VALUE, 1)
    }
}
