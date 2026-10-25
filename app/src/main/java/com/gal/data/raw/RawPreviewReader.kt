package com.gal.data.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Finds the JPEG previews embedded in a camera RAW file without reading the sensor data.
 *
 * Most RAW formats (ARW, DNG, CR2, NEF, ORF, PEF, RW2, ...) are TIFF containers, so this walks the
 * IFD tree (IFD0 -> IFD1, SubIFDs, Exif IFD, Sony MakerNote) collecting every JPEG it can find.
 * Fuji RAF has its own header that points at the JPEG directly.
 */
class RawPreviewReader(private val channel: FileChannel) {

    class Preview(val offset: Long, val length: Long, val width: Int, val height: Int) {
        val longSide: Int get() = maxOf(width, height)
    }

    class Result(val previews: List<Preview>, val orientation: Int)

    private val fileSize = channel.size()
    private var order = ByteOrder.LITTLE_ENDIAN
    private val visited = HashSet<Long>()
    private val candidates = LinkedHashMap<Long, Long>() // offset -> length
    private var orientation = 0

    fun read(): Result? {
        val head = bytes(0, 16) ?: return null
        val previews = if (head.startsWith("FUJIFILMCCD-RAW")) {
            readRaf()
        } else {
            readTiff(head)
        }
        if (previews.isEmpty()) return null
        return Result(previews.sortedBy { it.longSide }, orientation)
    }

    // ---- Fuji RAF: big-endian u32 JPEG offset at 84, length at 88 ----

    private fun readRaf(): List<Preview> {
        val h = buf(84, 8, ByteOrder.BIG_ENDIAN) ?: return emptyList()
        addCandidate(h.getInt(0).toLong() and 0xFFFFFFFFL, h.getInt(4).toLong() and 0xFFFFFFFFL)
        return validate()
    }

    // ---- TIFF-based containers ----

    private fun readTiff(head: ByteBuffer): List<Preview> {
        order = when {
            head.get(0) == 'I'.code.toByte() && head.get(1) == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            head.get(0) == 'M'.code.toByte() && head.get(1) == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return emptyList()
        }
        head.order(order)
        // 42 = TIFF, 0x55 = Panasonic RW2, 0x4F52 / 0x5352 = Olympus ORF
        val magic = head.getShort(2).toInt() and 0xFFFF
        if (magic != 42 && magic != 0x55 && magic != 0x4F52 && magic != 0x5352) return emptyList()
        walkChain(head.getInt(4).toLong() and 0xFFFFFFFFL, depth = 0, primary = true)
        return validate()
    }

    private fun walkChain(start: Long, depth: Int, primary: Boolean) {
        if (depth > 4) return
        var offset = start
        var first = primary
        var guard = 0
        while (offset > 0 && guard++ < 8 && visited.add(offset)) {
            offset = walkIfd(offset, depth, readOrientation = first)
            first = false
        }
    }

    /** Parses one IFD; returns the offset of the next IFD in the chain (0 = none). */
    private fun walkIfd(offset: Long, depth: Int, readOrientation: Boolean): Long {
        val countBuf = buf(offset, 2) ?: return 0
        val count = countBuf.getShort(0).toInt() and 0xFFFF
        if (count == 0 || count > 1024) return 0
        val entries = buf(offset + 2, count * 12 + 4) ?: return 0

        var compression = 0
        var stripOffset = 0L
        var stripBytes = 0L
        var stripCount = 0L
        var jpegOffset = 0L
        var jpegLength = 0L
        val subIfds = ArrayList<Long>()
        var exifIfd = 0L
        var makerNote: Pair<Long, Long>? = null

        for (i in 0 until count) {
            val base = i * 12
            val tag = entries.getShort(base).toInt() and 0xFFFF
            val type = entries.getShort(base + 2).toInt() and 0xFFFF
            val n = entries.getInt(base + 4).toLong() and 0xFFFFFFFFL
            val valuePos = base + 8
            when (tag) {
                0x0103 -> compression = inlineInt(entries, type, valuePos)
                0x0112 -> if (readOrientation) orientation = inlineInt(entries, type, valuePos)
                0x0111 -> { stripCount = n; stripOffset = firstValue(entries, type, n, valuePos) }
                0x0117 -> stripBytes = firstValue(entries, type, n, valuePos)
                0x0201 -> jpegOffset = firstValue(entries, type, n, valuePos)
                0x0202 -> jpegLength = firstValue(entries, type, n, valuePos)
                0x014A -> subIfds += values(entries, type, n, valuePos)
                0x8769 -> exifIfd = entries.getInt(valuePos).toLong() and 0xFFFFFFFFL
                // Panasonic RW2 keeps the full-size JPEG as an UNDEFINED blob in IFD0
                0x002E -> if (type == 7 && n > 1024) addCandidate(entries.getInt(valuePos).toLong() and 0xFFFFFFFFL, n)
                0x927C -> if (n > 16) makerNote = (entries.getInt(valuePos).toLong() and 0xFFFFFFFFL) to n
            }
        }

        if (jpegOffset > 0 && jpegLength > 0) addCandidate(jpegOffset, jpegLength)
        if ((compression == 6 || compression == 7) && stripCount == 1L && stripOffset > 0 && stripBytes > 0) {
            addCandidate(stripOffset, stripBytes)
        }
        subIfds.forEach { walkChain(it, depth + 1, primary = false) }
        if (exifIfd > 0) walkChain(exifIfd, depth + 1, primary = false)
        makerNote?.let { (at, _) -> readSonyMakerNote(at) }

        return entries.getInt(count * 12).toLong() and 0xFFFFFFFFL
    }

    /** Sony MakerNote tag 0x2001 is the full-size-ish PreviewImage that ARW files rely on. */
    private fun readSonyMakerNote(at: Long) {
        val sig = bytes(at, 12) ?: return
        if (!(sig.startsWith("SONY DSC") || sig.startsWith("SONY CAM") || sig.startsWith("SONY MOBILE"))) return
        val ifd = at + 12
        val count = (buf(ifd, 2)?.getShort(0)?.toInt() ?: return) and 0xFFFF
        if (count == 0 || count > 512) return
        val entries = buf(ifd + 2, count * 12) ?: return
        for (i in 0 until count) {
            val base = i * 12
            val tag = entries.getShort(base).toInt() and 0xFFFF
            if (tag != 0x2001) continue
            val n = entries.getInt(base + 4).toLong() and 0xFFFFFFFFL
            val ptr = entries.getInt(base + 8).toLong() and 0xFFFFFFFFL
            // Offsets are file-absolute on ARW, MakerNote-relative on a few bodies; the JPEG
            // magic check in validate() discards whichever is wrong.
            addCandidate(ptr, n)
            addCandidate(at + ptr, n)
        }
    }

    // ---- candidate validation ----

    private fun addCandidate(offset: Long, length: Long) {
        if (offset <= 0 || length < MIN_PREVIEW_BYTES || offset + length > fileSize) return
        candidates.putIfAbsent(offset, length)
    }

    private fun validate(): List<Preview> = candidates.mapNotNull { (off, len) ->
        val magic = bytes(off, 3) ?: return@mapNotNull null
        if (magic.get(0) != 0xFF.toByte() || magic.get(1) != 0xD8.toByte() || magic.get(2) != 0xFF.toByte()) {
            return@mapNotNull null
        }
        val (w, h) = jpegSize(off, len) ?: return@mapNotNull null
        Preview(off, len, w, h)
    }

    /** Walks JPEG segments to the first baseline/progressive SOF; null for lossless-JPEG sensor data. */
    private fun jpegSize(offset: Long, length: Long): Pair<Int, Int>? {
        var pos = offset + 2
        val end = offset + length
        var guard = 0
        while (pos + 4 <= end && guard++ < 64) {
            val seg = buf(pos, 4, ByteOrder.BIG_ENDIAN) ?: return null
            if (seg.get(0) != 0xFF.toByte()) return null
            val marker = seg.get(1).toInt() and 0xFF
            val len = seg.getShort(2).toInt() and 0xFFFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (marker == 0xC3) return null // lossless: raw sensor data, not a preview
                val sof = buf(pos + 5, 4, ByteOrder.BIG_ENDIAN) ?: return null
                val h = sof.getShort(0).toInt() and 0xFFFF
                val w = sof.getShort(2).toInt() and 0xFFFF
                return if (w > 0 && h > 0) w to h else null
            }
            if (marker == 0xDA || marker == 0xD9) return null
            pos += 2 + len
        }
        return null
    }

    // ---- low-level helpers ----

    private fun buf(offset: Long, len: Int, byteOrder: ByteOrder = order): ByteBuffer? {
        if (offset < 0 || len < 0 || offset + len > fileSize) return null
        val b = ByteBuffer.allocate(len)
        var pos = offset
        while (b.hasRemaining()) {
            val n = channel.read(b, pos)
            if (n < 0) return null
            pos += n
        }
        b.flip()
        return b.order(byteOrder)
    }

    private fun bytes(offset: Long, len: Int): ByteBuffer? = buf(offset, len)

    private fun ByteBuffer.startsWith(s: String): Boolean {
        if (remaining() < s.length) return false
        return s.indices.all { get(it) == s[it].code.toByte() }
    }

    private fun typeSize(type: Int) = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 0 }

    private fun inlineInt(b: ByteBuffer, type: Int, pos: Int): Int =
        if (type == 3) b.getShort(pos).toInt() and 0xFFFF else b.getInt(pos)

    private fun firstValue(b: ByteBuffer, type: Int, n: Long, pos: Int): Long =
        values(b, type, minOf(n, 1L), pos).firstOrNull() ?: 0L

    private fun values(b: ByteBuffer, type: Int, n: Long, pos: Int): List<Long> {
        val size = typeSize(type)
        if ((type != 3 && type != 4) || n <= 0 || n > 64) return emptyList()
        val total = size * n.toInt()
        val data = if (total <= 4) b.duplicate().order(order).apply { position(pos) } else {
            buf(b.getInt(pos).toLong() and 0xFFFFFFFFL, total) ?: return emptyList()
        }
        val base = if (total <= 4) pos else 0
        return List(n.toInt()) { i ->
            if (type == 3) (data.getShort(base + i * 2).toLong() and 0xFFFF)
            else data.getInt(base + i * 4).toLong() and 0xFFFFFFFFL
        }
    }

    private companion object {
        const val MIN_PREVIEW_BYTES = 8 * 1024L
    }
}
