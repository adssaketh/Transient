package com.demonlab.lune.tools

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A light WAV reader: sample rate, bit depth and the ID3 text tags, WITHOUT loading the embedded cover picture.
 *
 * jaudiotagger loads every frame of a WAV's ID3 chunk into memory, including a cover picture that can be tens of MB. The
 * library scan did that for every WAV on every start, and in the logs the app reached ~450 MB until Android reported
 * "not responding". This reader only walks chunk and frame HEADERS (pictures are skipped by seeking past them) and
 * reads the few small text frames that are needed.
 *
 * It handles plain ID3v2.3 / v2.4 tags. For anything else (RF64, tags with unsynchronisation, no ID3 chunk, ...) it says
 * so and the caller falls back to jaudiotagger exactly as before.
 */
object WavTags {
    private const val MAX_CHUNKS = 2000
    private const val MAX_TEXT_FRAME = 256 * 1024L

    class Layout(val sampleRate: Int, val bitsPerSample: Int, val id3Offset: Long, val id3Size: Long)
    class Picture(val offset: Long, val length: Long)
    class PictureLookup(val handled: Boolean, val picture: Picture?)

    // ------------------------------------------------------------------------------------------------ small helpers
    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun syncsafe(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0x7F) shl 21) or ((b[o + 1].toLong() and 0x7F) shl 14) or
            ((b[o + 2].toLong() and 0x7F) shl 7) or (b[o + 3].toLong() and 0x7F)

    // ------------------------------------------------------------------------------------------------ RIFF layout
    /** Walks the RIFF chunk headers only. Returns null when this is not a plain RIFF/WAVE file. */
    fun layout(file: File): Layout? {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 12) return null
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
            var pos = 12L
            var sampleRate = 0
            var bits = 0
            var id3Offset = -1L
            var id3Size = 0L
            var count = 0
            val hdr = ByteArray(8)
            while (pos + 8 <= len && count++ < MAX_CHUNKS) {
                raf.seek(pos)
                raf.readFully(hdr)
                val id = String(hdr, 0, 4, Charsets.ISO_8859_1)
                val size = le32(hdr, 4)
                val payload = pos + 8
                if (id == "fmt " && size >= 16) {
                    val f = ByteArray(16)
                    raf.seek(payload)
                    raf.readFully(f)
                    sampleRate = le32(f, 4).toInt()
                    bits = le16(f, 14)
                } else if ((id == "id3 " || id == "ID3 ") && id3Offset < 0) {
                    id3Offset = payload
                    id3Size = minOf(size, len - payload)
                }
                if (id == "data" && (size == 0L || size == 0xFFFFFFFFL)) break // streaming placeholder: cannot walk on
                val next = payload + size + (size and 1L)
                if (next <= pos) break
                pos = next
            }
            if (sampleRate <= 0 || bits <= 0) return null
            return Layout(sampleRate, bits, id3Offset, id3Size)
        }
    }

    // ------------------------------------------------------------------------------------------------ ID3 frames
    /**
     * Calls [visit] for each frame of the ID3v2.3/2.4 tag stored at [offset] (header only: id, payload offset, payload
     * size, and whether the frame uses flags this reader does not support). [visit] returns false to stop.
     * Returns false when the tag is not something this reader handles.
     */
    private fun walkFrames(
        raf: RandomAccessFile,
        offset: Long,
        size: Long,
        visit: (id: String, payload: Long, frameSize: Long, unsupported: Boolean) -> Boolean
    ): Boolean {
        if (offset < 0 || size < 10) return false
        val h = ByteArray(10)
        raf.seek(offset)
        raf.readFully(h)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return false
        val major = h[3].toInt()
        if (major != 3 && major != 4) return false
        val flags = h[5].toInt() and 0xFF
        if (flags and 0x80 != 0) return false // unsynchronisation: leave it to jaudiotagger
        val end = offset + minOf(size, syncsafe(h, 6) + 10L)
        var pos = offset + 10
        if (flags and 0x40 != 0) { // extended header: skip it
            val e = ByteArray(4)
            raf.seek(pos)
            raf.readFully(e)
            pos += if (major == 4) syncsafe(e, 0) else be32(e, 0) + 4L
        }
        val fh = ByteArray(10)
        while (pos + 10 <= end) {
            raf.seek(pos)
            raf.readFully(fh)
            if (fh[0].toInt() == 0) break // padding: the frames are over
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val frameSize = if (major == 4) syncsafe(fh, 4) else be32(fh, 4)
            val formatFlags = fh[9].toInt() and 0xFF
            val unsupported = if (major == 4) (formatFlags and 0x4F) != 0 else (formatFlags and 0xE0) != 0
            val payload = pos + 10
            if (frameSize < 0 || payload + frameSize > end) break
            if (!visit(id, payload, frameSize, unsupported)) break
            pos = payload + frameSize
        }
        return true
    }

    private fun decodeText(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val charset = when (b[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16 // with byte order mark
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        val text = String(b, 1, b.size - 1, charset).substringBefore('\u0000').trim()
        return text.ifEmpty { null }
    }

    class Tag(
        val title: String?, val artist: String?, val albumArtist: String?, val album: String?,
        val genre: String?, val track: String?, val composer: String?
    )

    /** The text tags of the ID3 chunk. Null when the tag is not something this reader handles. */
    fun readTag(file: File, offset: Long, size: Long): Tag? {
        RandomAccessFile(file, "r").use { raf ->
            val values = arrayOfNulls<String>(7)
            val ok = walkFrames(raf, offset, size) { id, payload, frameSize, unsupported ->
                val slot = when (id) {
                    "TIT2" -> 0; "TPE1" -> 1; "TPE2" -> 2; "TALB" -> 3; "TCON" -> 4; "TRCK" -> 5; "TCOM" -> 6
                    else -> -1
                }
                if (slot >= 0 && values[slot] == null && !unsupported && frameSize in 1..MAX_TEXT_FRAME) {
                    val b = ByteArray(frameSize.toInt())
                    raf.seek(payload)
                    raf.readFully(b)
                    values[slot] = decodeText(b)
                }
                true
            }
            if (!ok) return null
            return Tag(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
        }
    }

    /** Where the cover picture's bytes are inside the file (front cover preferred), without reading them. */
    fun findPicture(file: File): PictureLookup {
        val l = layout(file) ?: return PictureLookup(false, null)
        if (l.id3Offset < 0) return PictureLookup(false, null)
        RandomAccessFile(file, "r").use { raf ->
            var found: Picture? = null
            val ok = walkFrames(raf, l.id3Offset, l.id3Size) { id, payload, frameSize, unsupported ->
                if (id == "APIC" && !unsupported && frameSize > 4) {
                    val n = minOf(frameSize, 4096L).toInt()
                    val b = ByteArray(n)
                    raf.seek(payload)
                    raf.readFully(b)
                    val enc = b[0].toInt()
                    var i = 1
                    while (i < n && b[i].toInt() != 0) i++ // MIME type, ends with a single 0
                    i++
                    val pictureType = if (i < n) b[i].toInt() else -1
                    i++
                    if (enc == 1 || enc == 2) { // UTF-16 description ends with 00 00 on a 2-byte boundary
                        while (i + 1 < n && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
                        i += 2
                    } else {
                        while (i < n && b[i].toInt() != 0) i++
                        i++
                    }
                    if (i in 1 until n && frameSize - i > 0) {
                        val candidate = Picture(payload + i, frameSize - i)
                        if (found == null || pictureType == 3) found = candidate
                    }
                }
                true // keep walking: a front cover (type 3) may follow another picture
            }
            return PictureLookup(ok, found)
        }
    }

    /** Everything the library scan needs from a WAV, or null when this reader cannot handle the file. */
    fun read(file: File): HiFiInfo? {
        val l = layout(file) ?: return null
        if (l.id3Offset < 0) return null // no ID3 chunk (maybe LIST/INFO tags): let jaudiotagger look
        val t = readTag(file, l.id3Offset, l.id3Size) ?: return null
        return HiFiInfo(l.sampleRate, l.bitsPerSample, t.title, t.artist, t.albumArtist, t.album, t.genre, t.track, t.composer)
    }
}

/** An InputStream over a section of a file, so a big picture can be decoded without first loading it into memory. */
class FileRegionInputStream(file: File, offset: Long, private var remaining: Long) : InputStream() {
    private val raf = RandomAccessFile(file, "r").also { it.seek(offset) }

    override fun read(): Int {
        if (remaining <= 0) return -1
        val v = raf.read()
        if (v >= 0) remaining--
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun close() {
        raf.close()
    }
}
