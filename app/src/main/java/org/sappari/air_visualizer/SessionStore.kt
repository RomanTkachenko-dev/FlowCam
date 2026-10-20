package org.sappari.air_visualizer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Camera-delivered Y samples, not sensor RAW and not the rendered overlay. */
data class RecordedFrame(
    val timestampNs: Long,
    val luma: ByteArray,
    val metadata: String,
    /** Optional display-only preview. Never use JPEG samples as measurement data. */
    val previewJpeg: ByteArray? = null
)

data class RecordedSession(
    val calibration: CalibrationData,
    val metadata: String,
    val frames: List<RecordedFrame>
)

/**
 * Versioned, lossless, bounded diagnostic format. All numbers are big endian;
 * metadata is length-prefixed UTF-8. No Java serialization or archive extraction.
 * Methods retain caller ownership of streams, including on failure.
 */
object SessionStore {
    const val EXTENSION = "air"
    const val MIME_TYPE = "application/octet-stream"
    const val MAX_FRAMES = 120
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 2_073_600
    const val MAX_RECORDING_BYTES = 40L * 1024L * 1024L
    const val MAX_DURATION_NS = 30_000_000_000L
    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_FRAME_METADATA_BYTES = 16 * 1024
    const val MAX_PREVIEW_BYTES = 512 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    const val MAX_COMPRESSED_BYTES = 66L * 1024L * 1024L
    private const val MAGIC = 0x41565331 // AVS1
    private const val VERSION = 1

    @Throws(IOException::class)
    fun write(out: OutputStream, session: RecordedSession) {
        val pixels = validateCalibration(session.calibration)
        validateCount(pixels, session.frames.size)
        // Validate everything before touching the destination.
        val metadata = encodeText(session.metadata, MAX_METADATA_BYTES)
        val frameMetadata = session.frames.map { encodeText(it.metadata, MAX_FRAME_METADATA_BYTES) }
        var previous = -1L
        val first = session.frames.firstOrNull()?.timestampNs ?: 0L
        var recordingBytes = pixels.toLong() * session.frames.size
        session.frames.forEach {
            validateTimestamp(it.timestampNs, previous, first)
            checkFormat(it.luma.size == pixels, "Frame dimensions do not match calibration")
            it.previewJpeg?.let { preview ->
                validatePreview(preview)
                recordingBytes += preview.size
            }
            checkFormat(recordingBytes <= MAX_RECORDING_BYTES, "Recording exceeds memory budget")
            previous = it.timestampNs
        }
        val totalBytes = 44L + pixels * 8L + metadata.size + recordingBytes +
            session.frames.indices.sumOf { 16L + frameMetadata[it].size }
        checkFormat(totalBytes <= MAX_UNCOMPRESSED_BYTES, "Session is too large")
        val nonClosing = object : FilterOutputStream(out) {
            override fun close() { flush() }
        }
        GZIPOutputStream(nonClosing, 64 * 1024).use { gzip ->
            val data = DataOutputStream(gzip)
            val calibration = session.calibration
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(calibration.width)
            data.writeInt(calibration.height)
            data.writeInt(session.frames.size)
            data.writeInt(if (calibration.denoise) 1 else 0)
            data.writeFloat(calibration.region.left)
            data.writeFloat(calibration.region.top)
            data.writeFloat(calibration.region.right)
            data.writeFloat(calibration.region.bottom)
            writeText(data, metadata)
            calibration.reference.forEach { data.writeFloat(it) }
            calibration.variance.forEach { data.writeFloat(it) }
            session.frames.forEachIndexed { index, frame ->
                data.writeLong(frame.timestampNs)
                writeText(data, frameMetadata[index])
                data.write(frame.luma)
                val preview = frame.previewJpeg
                data.writeInt(preview?.size ?: 0)
                if (preview != null) data.write(preview)
            }
            data.flush()
        }
    }

    @Throws(IOException::class)
    fun read(input: InputStream): RecordedSession {
        val nonClosing = object : FilterInputStream(input) {
            override fun close() = Unit
        }
        val compressed = BoundedInput(nonClosing, MAX_COMPRESSED_BYTES)
        GZIPInputStream(compressed, 64 * 1024).use { gzip ->
            val data = DataInputStream(BoundedInput(gzip, MAX_UNCOMPRESSED_BYTES))
            checkFormat(data.readInt() == MAGIC, "Not an AirVisualizer session")
            checkFormat(data.readInt() == VERSION, "Unsupported session version")
            val width = data.readInt()
            val height = data.readInt()
            val pixels = validateDimensions(width, height)
            val frameCount = data.readInt()
            validateCount(pixels, frameCount)
            val flags = data.readInt()
            checkFormat(flags == 0 || flags == 1, "Invalid session flags")
            val left = data.readFloat()
            val top = data.readFloat()
            val right = data.readFloat()
            val bottom = data.readFloat()
            validateRegion(left, top, right, bottom)
            val metadata = readText(data, MAX_METADATA_BYTES)
            val reference = FloatArray(pixels)
            for (i in reference.indices) {
                val value = data.readFloat()
                checkFormat(value.isFinite() && value in 0f..255f, "Invalid reference pixel")
                reference[i] = value
            }
            val variance = FloatArray(pixels)
            for (i in variance.indices) {
                val value = data.readFloat()
                checkFormat(value.isFinite() && value in 0f..65025f, "Invalid noise variance")
                variance[i] = value
            }
            val frames = ArrayList<RecordedFrame>(frameCount)
            var previous = -1L
            var first = 0L
            var recordingBytes = pixels.toLong() * frameCount
            repeat(frameCount) { index ->
                val timestamp = data.readLong()
                if (index == 0) first = timestamp
                validateTimestamp(timestamp, previous, first)
                val frameMetadata = readText(data, MAX_FRAME_METADATA_BYTES)
                val luma = ByteArray(pixels)
                data.readFully(luma)
                val previewLength = data.readInt()
                checkFormat(previewLength in 0..MAX_PREVIEW_BYTES, "Invalid preview length")
                recordingBytes += previewLength
                checkFormat(recordingBytes <= MAX_RECORDING_BYTES, "Recording exceeds memory budget")
                val preview = if (previewLength == 0) null else ByteArray(previewLength).also {
                    data.readFully(it)
                    validatePreview(it)
                }
                frames.add(RecordedFrame(timestamp, luma, frameMetadata, preview))
                previous = timestamp
            }
            // Consume the gzip trailer so truncated streams and CRC failures cannot pass.
            checkFormat(data.read() == -1, "Unexpected data after session")
            return RecordedSession(
                CalibrationData(width, height, reference, variance, flags == 1,
                    NormalizedRegion(left, top, right, bottom)),
                metadata,
                frames
            )
        }
    }

    private fun validateCalibration(calibration: CalibrationData): Int {
        val pixels = validateDimensions(calibration.width, calibration.height)
        checkFormat(calibration.reference.size == pixels && calibration.variance.size == pixels,
            "Calibration dimensions do not match arrays")
        checkFormat(calibration.reference.all { it.isFinite() && it in 0f..255f }, "Invalid reference pixel")
        checkFormat(calibration.variance.all { it.isFinite() && it in 0f..65025f }, "Invalid noise variance")
        val region = calibration.region
        validateRegion(region.left, region.top, region.right, region.bottom)
        return pixels
    }

    private fun validateDimensions(width: Int, height: Int): Int {
        checkFormat(width in 2..MAX_DIMENSION && height in 2..MAX_DIMENSION, "Invalid frame dimensions")
        val pixels = width.toLong() * height
        checkFormat(pixels <= MAX_PIXELS, "Frame dimensions exceed the supported limit")
        return pixels.toInt()
    }

    private fun validateRegion(left: Float, top: Float, right: Float, bottom: Float) {
        checkFormat(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
            left < right && top < bottom, "Invalid analysis region")
    }

    private fun validateCount(pixels: Int, frameCount: Int) {
        checkFormat(frameCount in 1..MAX_FRAMES, "Invalid frame count")
        checkFormat(pixels.toLong() * frameCount <= MAX_RECORDING_BYTES, "Recording exceeds memory budget")
    }

    private fun validateTimestamp(timestamp: Long, previous: Long, first: Long) {
        checkFormat(timestamp >= 0 && timestamp > previous, "Frame timestamps must be increasing")
        checkFormat(timestamp - first <= MAX_DURATION_NS, "Recording exceeds duration limit")
    }

    private fun validatePreview(preview: ByteArray) {
        checkFormat(preview.size in 4..MAX_PREVIEW_BYTES &&
            preview[0] == 0xff.toByte() && preview[1] == 0xd8.toByte() &&
            preview[preview.lastIndex - 1] == 0xff.toByte() && preview.last() == 0xd9.toByte(),
            "Invalid JPEG preview")
        // A renderer must also inspect JPEG dimensions before decoding; compressed
        // byte limits alone do not prevent oversized bitmap allocations.
    }

    private fun encodeText(text: String, maxBytes: Int): ByteArray {
        checkFormat(text.length <= maxBytes, "Metadata is too long")
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(text))
        checkFormat(buffer.remaining() <= maxBytes, "Metadata is too long")
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun writeText(data: DataOutputStream, bytes: ByteArray) {
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readText(data: DataInputStream, maxBytes: Int): String {
        val length = data.readInt()
        checkFormat(length in 0..maxBytes, "Invalid metadata length")
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun checkFormat(valid: Boolean, message: String) {
        if (!valid) throw IOException(message)
    }

    private class BoundedInput(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = `in`.read()
            if (value >= 0) consume(1)
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            // Permit one extra byte only to distinguish exact-limit EOF from excess data.
            val allowed = minOf(length.toLong(), maximum - count + 1).coerceAtLeast(0).toInt()
            if (allowed == 0 && length > 0) throw IOException("Session exceeds size limit")
            val size = `in`.read(bytes, offset, allowed)
            if (size > 0) consume(size)
            return size
        }
        override fun skip(n: Long): Long {
            val skipped = `in`.skip(minOf(n, maximum - count + 1).coerceAtLeast(0))
            count += skipped
            if (count > maximum) throw IOException("Session exceeds size limit")
            return skipped
        }
        private fun consume(size: Int) {
            count += size
            if (count > maximum) throw IOException("Session exceeds size limit")
        }
    }
}
