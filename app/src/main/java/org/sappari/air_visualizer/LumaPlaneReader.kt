package org.sappari.air_visualizer

import java.nio.ByteBuffer

/** Copies only the Y plane. CameraX YUV planes need not have packed rows or chroma. */
object LumaPlaneReader {
    fun read(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0) { "Invalid image dimensions" }
        require(rowStride > 0 && pixelStride > 0) { "Invalid plane strides" }
        val rowBytes = (width - 1L) * pixelStride + 1L
        require(rowStride >= rowBytes) { "Rows overlap" }
        val count = width.toLong() * height
        require(count <= Int.MAX_VALUE) { "Image is too large" }
        val base = buffer.position()
        val last = base.toLong() + (height - 1L) * rowStride + rowBytes - 1L
        require(last < buffer.limit()) { "Y plane does not contain every pixel" }
        val source = buffer.duplicate()
        val result = ByteArray(count.toInt())
        for (row in 0 until height) {
            val start = base + row * rowStride
            if (pixelStride == 1) {
                source.position(start)
                source.get(result, row * width, width)
            } else {
                for (column in 0 until width) {
                    result[row * width + column] = source.get(start + column * pixelStride)
                }
            }
        }
        return result
    }
}
