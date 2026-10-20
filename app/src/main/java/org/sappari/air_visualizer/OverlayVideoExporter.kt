package org.sappari.air_visualizer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Offline AVC/MP4 export of already rendered overlays, with original capture timing.
 * Call on a worker thread. finish() commits the file; close() otherwise deletes it.
 * No camera/audio permission or screen capture is involved.
 */
class OverlayVideoExporter(
    private val width: Int,
    private val height: Int,
    private val output: File
) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var track = -1
    private var firstTimestampNs = -1L
    private var previousTimestampNs = -1L
    private var previousPtsUs = -1L
    private var previousIntervalUs = 100_000L
    private var samplesWritten = 0
    private var finished = false
    private var released = false
    private var outputEos = false
    private var colorFormat = 0
    private var ownsOutput = false
    private val pixels: IntArray
    private val yuv: ByteArray
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Video dimensions must be positive and even"
        }
        require(width.toLong() * height <= SessionStore.MAX_PIXELS) { "Video dimensions are too large" }
        require(!output.exists() || output.length() == 0L) { "Output file already contains data" }
        pixels = IntArray(width * height)
        yuv = ByteArray(width * height * 3 / 2)
        try {
            val candidate = findEncoder(width, height)
            colorFormat = candidate.second
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 8).coerceIn(1_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            codec = MediaCodec.createByCodecName(candidate.first).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            ownsOutput = true
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (failure: Exception) {
            releaseResources()
            if (ownsOutput) output.delete()
            throw failure
        }
    }

    fun addFrame(bitmap: Bitmap, timestampNs: Long) {
        check(!released && !finished) { "Exporter is closed" }
        require(bitmap.width == width && bitmap.height == height) { "Bitmap dimensions differ from video" }
        require(timestampNs >= 0 && timestampNs > previousTimestampNs) { "Frame timestamps must increase" }
        try {
            checkCancelled()
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            ArgbYuv420.convert(pixels, width, height, yuv, semiPlanar = false)
            if (firstTimestampNs < 0) firstTimestampNs = timestampNs
            val ptsUs = maxOf((timestampNs - firstTimestampNs) / 1000L, previousPtsUs + 1L)
            if (previousPtsUs >= 0) previousIntervalUs = ptsUs - previousPtsUs
            val encoder = requireNotNull(codec)
            val index = acquireInput()
            // Image planes expose vendor stride/pixel layout; never assume camera NV21 order.
            val image = encoder.getInputImage(index)
            if (image != null) {
                try { copyToImage(image) } finally { image.close() }
            } else {
                val buffer = encoder.getInputBuffer(index) ?: throw IOException("Encoder input is unavailable")
                buffer.clear()
                if (buffer.remaining() < yuv.size) throw IOException("Encoder input is too small")
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    ArgbYuv420.convert(pixels, width, height, yuv, semiPlanar = true)
                }
                buffer.put(yuv)
            }
            encoder.queueInputBuffer(index, 0, yuv.size, ptsUs, 0)
            previousTimestampNs = timestampNs
            previousPtsUs = ptsUs
            drain(waitForEos = false)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun finish() {
        if (finished) return
        check(!released) { "Exporter is closed" }
        try {
            check(previousPtsUs >= 0) { "Cannot export an empty recording" }
            val index = acquireInput()
            requireNotNull(codec).queueInputBuffer(index, 0, 0, previousPtsUs + previousIntervalUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(waitForEos = true)
            check(muxerStarted && samplesWritten > 0 && outputEos) { "Encoder produced no valid video" }
            requireNotNull(muxer).stop()
            muxerStarted = false
            finished = true
            releaseResources()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    override fun close() {
        releaseResources()
        if (!finished && ownsOutput) output.delete()
    }

    private fun acquireInput(): Int {
        val deadline = System.nanoTime() + TIMEOUT_NS
        while (true) {
            checkCancelled()
            val index = requireNotNull(codec).dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(waitForEos = false)
            if (System.nanoTime() >= deadline) throw IOException("Video encoder input timed out")
        }
    }

    private fun drain(waitForEos: Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NS
        val encoder = requireNotNull(codec)
        while (true) {
            checkCancelled()
            when (val index = encoder.dequeueOutputBuffer(bufferInfo, if (waitForEos) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEos) return
                    if (System.nanoTime() >= deadline) throw IOException("Video encoder output timed out")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed twice" }
                    track = requireNotNull(muxer).addTrack(encoder.outputFormat)
                    requireNotNull(muxer).start()
                    muxerStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    try {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "Encoder data arrived before track format" }
                            val bytes = encoder.getOutputBuffer(index) ?: throw IOException("Encoder output is unavailable")
                            bytes.position(bufferInfo.offset)
                            bytes.limit(bufferInfo.offset + bufferInfo.size)
                            requireNotNull(muxer).writeSampleData(track, bytes, bufferInfo)
                            samplesWritten++
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (outputEos) return
                }
            }
        }
    }

    private fun copyToImage(image: Image) {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size != 3) {
            throw IOException("Encoder does not expose supported YUV420 image planes")
        }
        val planeSizes = intArrayOf(width * height, width * height / 4, width * height / 4)
        var source = 0
        image.planes.forEachIndexed { planeIndex, plane ->
            val planeWidth = if (planeIndex == 0) width else width / 2
            val planeHeight = if (planeIndex == 0) height else height / 2
            val target = plane.buffer
            val base = target.position()
            for (row in 0 until planeHeight) {
                for (column in 0 until planeWidth) {
                    target.put(base + row * plane.rowStride + column * plane.pixelStride,
                        yuv[source + row * planeWidth + column])
                }
            }
            source += planeSizes[planeIndex]
        }
    }

    private fun releaseResources() {
        if (released) return
        released = true
        codec?.let { encoder ->
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        codec = null
        muxer?.let { writer ->
            if (muxerStarted) runCatching { writer.stop() }
            runCatching { writer.release() }
        }
        muxer = null
        muxerStarted = false
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Export cancelled")
    }

    companion object {
        private const val MIME = "video/avc"
        private const val TIMEOUT_NS = 10_000_000_000L

        @Suppress("DEPRECATION")
        private fun findEncoder(width: Int, height: Int): Pair<String, Int> {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
                .sortedBy { if (it.isSoftwareOnly) 0 else 1 }
            for (codec in codecs) {
                val capabilities = runCatching { codec.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
                if (capabilities.videoCapabilities?.isSizeSupported(width, height) != true) continue
                val format = listOf(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                ).firstOrNull { capabilities.colorFormats.contains(it) } ?: continue
                return codec.name to format
            }
            throw IOException("No compatible AVC encoder for this recording size")
        }
    }
}

/** Pure JVM conversion: BT.601 limited-range Y with averaged 2x2 chroma, I420 or NV12. */
internal object ArgbYuv420 {
    fun convert(argb: IntArray, width: Int, height: Int, destination: ByteArray, semiPlanar: Boolean) {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0)
        val count = width.toLong() * height
        require(count <= Int.MAX_VALUE / 2 && argb.size == count.toInt())
        val pixels = count.toInt()
        require(destination.size == pixels * 3 / 2)
        for (i in argb.indices) {
            val color = argb[i]
            val r = color ushr 16 and 255
            val g = color ushr 8 and 255
            val b = color and 255
            destination[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235).toByte()
        }
        var chroma = 0
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val color = argb[(y + dy) * width + x + dx]
                    r += color ushr 16 and 255
                    g += color ushr 8 and 255
                    b += color and 255
                }
                r = (r + 2) / 4
                g = (g + 2) / 4
                b = (b + 2) / 4
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
                if (semiPlanar) {
                    destination[pixels + chroma * 2] = u
                    destination[pixels + chroma * 2 + 1] = v
                } else {
                    destination[pixels + chroma] = u
                    destination[pixels + pixels / 4 + chroma] = v
                }
                chroma++
            }
        }
    }
}
