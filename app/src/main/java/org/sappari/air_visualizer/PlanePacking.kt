package org.sappari.air_visualizer

import java.nio.ByteBuffer

/** A pure helper, shared by Y, U and V planes; honors each buffer's position/limit. */
object PlanePacking {
    fun copy(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
             destination: ByteArray, offset: Int, step: Int) {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0 && step > 0 && offset >= 0)
        val rowBytes = (width - 1L) * pixelStride + 1
        require(rowStride >= rowBytes)
        val last = (height - 1L) * rowStride + rowBytes
        require(last <= source.remaining())
        require(offset.toLong() + (width.toLong() * height - 1) * step < destination.size)
        val origin = source.position()
        if (pixelStride == 1 && step == 1) {
            val reader = source.duplicate()
            for (row in 0 until height) {
                reader.position(origin + row * rowStride)
                reader.get(destination, offset + row * width, width)
            }
            return
        }
        var dst = offset
        for (row in 0 until height) for (col in 0 until width) {
            destination[dst] = source.get(origin + row * rowStride + col * pixelStride)
            dst += step
        }
    }
}
