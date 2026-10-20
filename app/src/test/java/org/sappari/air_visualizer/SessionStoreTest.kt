package org.sappari.air_visualizer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class SessionStoreTest {
    private fun calibration() = CalibrationData(2, 2,
        floatArrayOf(0f, 127.125f, 128.75f, 255f),
        floatArrayOf(0f, 0.25f, 5.5f, 1024f), true,
        NormalizedRegion(0.1f, 0.2f, 0.9f, 0.8f))

    private fun session() = RecordedSession(calibration(), "{\"scene\":\"Фен — холодний\"}", listOf(
        RecordedFrame(4_000_000_000, byteArrayOf(0, 127, 128.toByte(), 255.toByte()), "{\"iso\":100}"),
        RecordedFrame(4_125_000_123, byteArrayOf(20, 30, 40, 50), "{\"iso\":101}",
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0xff.toByte(), 0xd9.toByte()))))

    private fun encode(value: RecordedSession = session()): ByteArray =
        ByteArrayOutputStream().also { SessionStore.write(it, value) }.toByteArray()

    private fun gzip(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(payload) }
    }.toByteArray()

    private fun raw(write: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { write(DataOutputStream(it)) }.toByteArray()

    private fun header(data: DataOutputStream, width: Int = 2, height: Int = 2, frames: Int = 1) {
        data.writeInt(0x41565331)
        data.writeInt(1)
        data.writeInt(width)
        data.writeInt(height)
        data.writeInt(frames)
    }

    private fun validPrefix(data: DataOutputStream) {
        header(data)
        data.writeInt(0)
        listOf(0f, 0f, 1f, 1f).forEach { data.writeFloat(it) }
        data.writeInt(0)
        repeat(4) { data.writeFloat(100f) }
        repeat(4) { data.writeFloat(2f) }
    }

    private fun expectRejected(bytes: ByteArray) {
        try {
            SessionStore.read(ByteArrayInputStream(bytes))
            fail("Invalid session must not be accepted")
        } catch (_: IOException) { }
    }

    @Test fun roundTripPreservesSamplesCalibrationTimestampsMetadataAndOptionalPreview() {
        val original = session()
        val actual = SessionStore.read(ByteArrayInputStream(encode(original)))
        assertEquals(original.metadata, actual.metadata)
        assertEquals(original.calibration.width, actual.calibration.width)
        assertEquals(original.calibration.height, actual.calibration.height)
        assertEquals(original.calibration.region, actual.calibration.region)
        assertEquals(original.calibration.denoise, actual.calibration.denoise)
        assertArrayEquals(original.calibration.reference, actual.calibration.reference, 0f)
        assertArrayEquals(original.calibration.variance, actual.calibration.variance, 0f)
        assertEquals(2, actual.frames.size)
        original.frames.zip(actual.frames).forEach { (a, b) ->
            assertEquals(a.timestampNs, b.timestampNs)
            assertEquals(a.metadata, b.metadata)
            assertArrayEquals(a.luma, b.luma)
            assertArrayEquals(a.previewJpeg, b.previewJpeg)
        }
    }

    @Test fun callerRetainsStreamOwnership() {
        var outputClosed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        SessionStore.write(output, session())
        assertFalse(outputClosed)
        var inputClosed = false
        val input = object : ByteArrayInputStream(output.toByteArray()) {
            override fun close() { inputClosed = true; super.close() }
        }
        SessionStore.read(input)
        assertFalse(inputClosed)
    }

    @Test fun preservesCallerInputOwnershipOnFailure() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { closed = true }
        }
        try { SessionStore.read(input); fail() } catch (_: IOException) { }
        assertFalse(closed)
    }

    @Test fun rejectsUnknownMagicAndVersion() {
        expectRejected(gzip(raw { it.writeInt(123); it.writeInt(1) }))
        expectRejected(gzip(raw { it.writeInt(0x41565331); it.writeInt(999) }))
    }

    @Test fun rejectsOverflowingDimensionsBeforeAllocation() {
        expectRejected(gzip(raw { header(it, Int.MAX_VALUE, Int.MAX_VALUE) }))
        expectRejected(gzip(raw { header(it, -1, 2) }))
        expectRejected(gzip(raw { header(it, 4096, 4096) }))
    }

    @Test fun rejectsHugeNegativeAndEmptyFrameCountsBeforeAllocation() {
        for (count in listOf(Int.MAX_VALUE, -1, 0, SessionStore.MAX_FRAMES + 1)) {
            expectRejected(gzip(raw { header(it, frames = count) }))
        }
    }

    @Test fun rejectsFrameMemoryBudgetBeforeAllocatingImages() {
        expectRejected(gzip(raw { header(it, 1920, 1080, 120) }))
    }

    @Test fun rejectsUnknownFlags() {
        expectRejected(gzip(raw { header(it); it.writeInt(8) }))
    }

    @Test fun rejectsNonFiniteAndReversedRegions() {
        for (region in listOf(listOf(Float.NaN, 0f, 1f, 1f), listOf(0.8f, 0f, 0.2f, 1f))) {
            expectRejected(gzip(raw { data ->
                header(data); data.writeInt(0); region.forEach { data.writeFloat(it) }
            }))
        }
    }

    @Test fun rejectsInvalidMetadataLengthsBeforeAllocation() {
        for (length in listOf(-1, SessionStore.MAX_METADATA_BYTES + 1, Int.MAX_VALUE)) {
            expectRejected(gzip(raw { data ->
                header(data); data.writeInt(0)
                listOf(0f, 0f, 1f, 1f).forEach { data.writeFloat(it) }
                data.writeInt(length)
            }))
        }
    }

    @Test fun rejectsMalformedUtf8RatherThanSilentlyReplacingIt() {
        expectRejected(gzip(raw { data ->
            header(data); data.writeInt(0)
            listOf(0f, 0f, 1f, 1f).forEach { data.writeFloat(it) }
            data.writeInt(2); data.write(byteArrayOf(0xc3.toByte(), 0x28))
        }))
    }

    @Test fun rejectsNonFiniteAndOutOfRangeCalibration() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 256f)) {
            expectRejected(gzip(raw { data ->
                header(data); data.writeInt(0)
                listOf(0f, 0f, 1f, 1f).forEach { data.writeFloat(it) }
                data.writeInt(0); data.writeFloat(value)
            }))
        }
        expectRejected(gzip(raw { data ->
            header(data); data.writeInt(0)
            listOf(0f, 0f, 1f, 1f).forEach { data.writeFloat(it) }
            data.writeInt(0); repeat(4) { data.writeFloat(1f) }; data.writeFloat(-1f)
        }))
    }

    @Test fun rejectsNegativeTimestampAndMalformedFrameMetadataLength() {
        expectRejected(gzip(raw { validPrefix(it); it.writeLong(-1) }))
        expectRejected(gzip(raw { validPrefix(it); it.writeLong(0); it.writeInt(Int.MAX_VALUE) }))
    }

    @Test fun rejectsOversizedAndInvalidJpegPreview() {
        expectRejected(gzip(raw { data ->
            validPrefix(data); data.writeLong(0); data.writeInt(0); data.write(ByteArray(4))
            data.writeInt(SessionStore.MAX_PREVIEW_BYTES + 1)
        }))
        expectRejected(gzip(raw { data ->
            validPrefix(data); data.writeLong(0); data.writeInt(0); data.write(ByteArray(4))
            data.writeInt(4); data.writeInt(123)
        }))
    }

    @Test fun rejectsTruncatedCompressedStreamIncludingMissingChecksum() {
        val bytes = encode()
        for (remove in listOf(1, 4, 8, bytes.size / 2)) expectRejected(bytes.copyOf(bytes.size - remove))
    }

    @Test fun rejectsTruncatedPayloadEvenWithValidGzipChecksum() {
        val payload = GZIPInputStream(ByteArrayInputStream(encode())).use { it.readBytes() }
        expectRejected(gzip(payload.copyOf(payload.size - 1)))
    }

    @Test fun rejectsChecksumCorruption() {
        val bytes = encode()
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 1).toByte()
        expectRejected(bytes)
    }

    @Test fun rejectsTrailingPayloadAndConcatenatedMember() {
        val payload = GZIPInputStream(ByteArrayInputStream(encode())).use { it.readBytes() }
        expectRejected(gzip(payload + byteArrayOf(99)))
        expectRejected(encode() + gzip(byteArrayOf(1)))
    }

    @Test fun rejectsRepeatedOrDecreasingTimestampsOnRead() {
        val payload = raw { data ->
            header(data, frames = 2); data.writeInt(0)
            listOf(0f, 0f, 1f, 1f).forEach { data.writeFloat(it) }
            data.writeInt(0); repeat(8) { data.writeFloat(1f) }
            repeat(2) {
                data.writeLong(100); data.writeInt(0); data.write(ByteArray(4)); data.writeInt(0)
            }
        }
        expectRejected(gzip(payload))
    }

    @Test fun invalidWriteLeavesDestinationUntouched() {
        val original = session()
        val invalid = original.copy(frames = listOf(original.frames[0], original.frames[0]))
        val destination = ByteArrayOutputStream()
        try { SessionStore.write(destination, invalid); fail() } catch (_: IOException) { }
        assertEquals(0, destination.size())
    }

    @Test fun rejectsLongRecordingsAndIncorrectSampleCountOnWrite() {
        val original = session()
        val invalids = listOf(
            original.copy(frames = listOf(original.frames[0], original.frames[1].copy(
                timestampNs = original.frames[0].timestampNs + SessionStore.MAX_DURATION_NS + 1))),
            original.copy(frames = listOf(original.frames[0].copy(luma = ByteArray(3)))),
            original.copy(frames = emptyList()))
        invalids.forEach {
            try { encode(it); fail() } catch (_: IOException) { }
        }
    }

    @Test fun accountsForPreviewBytesInRecordingBudget() {
        val calibration = CalibrationData(640, 480, FloatArray(640 * 480), FloatArray(640 * 480),
            false, NormalizedRegion(0f, 0f, 1f, 1f))
        val preview = ByteArray(SessionStore.MAX_PREVIEW_BYTES)
        preview[0] = 0xff.toByte(); preview[1] = 0xd8.toByte()
        preview[preview.lastIndex - 1] = 0xff.toByte(); preview[preview.lastIndex] = 0xd9.toByte()
        val frames = List(60) { RecordedFrame(it.toLong(), ByteArray(640 * 480), "", preview) }
        val destination = ByteArrayOutputStream()
        try { SessionStore.write(destination, RecordedSession(calibration, "", frames)); fail() }
        catch (_: IOException) { }
        assertEquals(0, destination.size())
    }
}
