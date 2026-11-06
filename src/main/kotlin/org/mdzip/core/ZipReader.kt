package org.mdzip.core

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** One central directory record, checked against its local header. */
internal class ZipRecord(
    val name: String,
    val flags: Int,
    val method: Int,
    val dosTime: Int,
    val dosDate: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val localOffset: Long,
    val versionMadeBy: Int,
    val externalAttrs: Long,
) {
    /** Offset of the entry's data, after its local header. */
    var dataOffset: Long = 0
    var localNameMatches: Boolean = true

    val isDirectory: Boolean get() = name.endsWith("/")
    private val unixType: Int get() = ((externalAttrs ushr 16) and 0xF000L).toInt()

    /** True for a symbolic link (Unix file type S_IFLNK). */
    val isSymlink: Boolean get() = unixType == 0xA000

    /** True for anything that is neither a regular file nor a directory. */
    val isSpecial: Boolean
        get() {
            val t = unixType
            if (t == 0) return false
            if (t == 0x8000) return isDirectory // a "regular file" whose name ends in / is not a directory
            if (t == 0x4000) return !isDirectory
            return true
        }

    val hasUtf8Flag: Boolean get() = (flags and 0x800) != 0
}

internal class ZipDirectory(val records: List<ZipRecord>, val usesZip64: Boolean, val zip64Needed: Boolean)

/**
 * Reads a ZIP archive from memory through its central directory (which governs, Section 4),
 * checking every record against its local header, rejecting overlapping data, and enforcing
 * [Limits] on declared sizes before any data is decompressed.
 */
internal object ZipReader {
    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_ZIP64_EOCD = 0x06064b50L
    private const val SIG_ZIP64_LOCATOR = 0x07064b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_LOCAL = 0x04034b50L

    private fun invalid(message: String, path: String? = null): Nothing = fail(MdzErrorCode.ZIP_INVALID, message, path)

    private fun u16(b: ByteArray, p: Long): Int {
        val i = p.toInt()
        return (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(b: ByteArray, p: Long): Long = u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)

    private fun u64(b: ByteArray, p: Long): Long {
        val v = u32(b, p) or (u32(b, p + 4) shl 32)
        if (v < 0) invalid("ZIP64 value out of range")
        return v
    }

    private fun need(b: ByteArray, p: Long, len: Long) {
        if (p < 0 || len < 0 || p + len > b.size) invalid("Archive is truncated or a ZIP offset is out of range")
    }

    fun read(b: ByteArray, limits: Limits): ZipDirectory {
        val n = b.size.toLong()
        if (n < 22) invalid("Not a ZIP archive")

        // End of central directory: search back over a possible comment.
        var eocd = -1L
        var p = n - 22
        val stop = maxOf(0L, n - 22 - 0xFFFF)
        while (p >= stop) {
            if (u32(b, p) == SIG_EOCD && p + 22 + u16(b, p + 20) <= n) { eocd = p; break }
            p--
        }
        if (eocd < 0) invalid("Not a ZIP archive (no end of central directory record)")

        var disk = u16(b, eocd + 4).toLong()
        var cdDisk = u16(b, eocd + 6).toLong()
        var countThisDisk = u16(b, eocd + 8).toLong()
        var count = u16(b, eocd + 10).toLong()
        var cdSize = u32(b, eocd + 12)
        var cdOffset = u32(b, eocd + 16)
        var cdEndLimit = eocd
        var usesZip64 = false

        val wantsZip64 = count == 0xFFFFL || countThisDisk == 0xFFFFL || cdSize == 0xFFFFFFFFL ||
            cdOffset == 0xFFFFFFFFL || disk == 0xFFFFL || cdDisk == 0xFFFFL
        if (eocd >= 20 && u32(b, eocd - 20) == SIG_ZIP64_LOCATOR) {
            val z = u64(b, eocd - 20 + 8)
            need(b, z, 56)
            if (u32(b, z) != SIG_ZIP64_EOCD) invalid("Bad ZIP64 end of central directory record")
            usesZip64 = true
            disk = u32(b, z + 16)
            cdDisk = u32(b, z + 20)
            countThisDisk = u64(b, z + 24)
            count = u64(b, z + 32)
            cdSize = u64(b, z + 40)
            cdOffset = u64(b, z + 48)
            cdEndLimit = z
        } else if (wantsZip64) {
            invalid("ZIP64 end of central directory record is missing")
        }
        if (disk != 0L || cdDisk != 0L || countThisDisk != count) invalid("Multi-volume archives are not supported")
        if (count > limits.maxEntries) {
            fail(MdzErrorCode.LIMIT_EXCEEDED, "Archive lists $count entries; the limit is ${limits.maxEntries}")
        }
        if (cdOffset + cdSize > cdEndLimit) invalid("Central directory is out of range")

        val records = ArrayList<ZipRecord>(count.toInt())
        var q = cdOffset
        val cdEnd = cdOffset + cdSize
        for (k in 0 until count) {
            need(b, q, 46)
            if (q + 46 > cdEnd || u32(b, q) != SIG_CENTRAL) invalid("Bad central directory record")
            val madeBy = u16(b, q + 4)
            val flags = u16(b, q + 8)
            val method = u16(b, q + 10)
            val time = u16(b, q + 12)
            val date = u16(b, q + 14)
            val crc = u32(b, q + 16)
            var csize = u32(b, q + 20)
            var usize = u32(b, q + 24)
            val nameLen = u16(b, q + 28)
            val extraLen = u16(b, q + 30)
            val commentLen = u16(b, q + 32)
            val diskStart = u16(b, q + 34)
            val external = u32(b, q + 38)
            var offset = u32(b, q + 42)
            val recEnd = q + 46 + nameLen + extraLen + commentLen
            if (recEnd > cdEnd) invalid("Central directory record runs past the directory")
            val nameBytes = b.copyOfRange((q + 46).toInt(), (q + 46 + nameLen).toInt())

            // ZIP64 extended information: present only for the fields that overflowed, in order.
            if (usize == 0xFFFFFFFFL || csize == 0xFFFFFFFFL || offset == 0xFFFFFFFFL || diskStart == 0xFFFF) {
                var e = q + 46 + nameLen
                val eEnd = e + extraLen
                var found = false
                while (e + 4 <= eEnd) {
                    val id = u16(b, e)
                    val len = u16(b, e + 2)
                    if (e + 4 + len > eEnd) break
                    if (id == 0x0001) {
                        var f = e + 4
                        val fEnd = f + len
                        fun next(): Long {
                            if (f + 8 > fEnd) invalid("ZIP64 extra field is too short")
                            return u64(b, f).also { f += 8 }
                        }
                        if (usize == 0xFFFFFFFFL) usize = next()
                        if (csize == 0xFFFFFFFFL) csize = next()
                        if (offset == 0xFFFFFFFFL) offset = next()
                        found = true
                        usesZip64 = true
                        break
                    }
                    e += 4 + len
                }
                if (!found) invalid("ZIP64 extra field is missing")
            } else if (diskStart != 0) {
                invalid("Multi-volume archives are not supported")
            }

            val name = decodeName(nameBytes, (flags and 0x800) != 0)
            records.add(ZipRecord(name, flags, method, time, date, crc, csize, usize, offset, madeBy, external))
            q = recEnd
        }
        if (q != cdEnd) invalid("Central directory size does not match its records")

        // Declared limits, before touching any entry data.
        var total = 0L
        for (r in records) {
            if (r.size > limits.maxEntryBytes) {
                fail(MdzErrorCode.LIMIT_EXCEEDED, "Entry declares ${r.size} bytes; the limit is ${limits.maxEntryBytes}", r.name)
            }
            total += r.size
            if (total > limits.maxTotalBytes) {
                fail(MdzErrorCode.LIMIT_EXCEEDED, "Entries declare more than ${limits.maxTotalBytes} bytes in total")
            }
            if (r.size > limits.ratioAppliesAboveBytes &&
                (r.compressedSize == 0L || r.size / r.compressedSize > limits.maxCompressionRatio)
            ) {
                fail(MdzErrorCode.LIMIT_EXCEEDED,
                    "Entry's compression ratio is above ${limits.maxCompressionRatio}:1", r.name)
            }
        }

        for (r in records) {
            if ((r.flags and 0x41) != 0 || r.method == 99) {
                fail(MdzErrorCode.ZIP_ENCRYPTED, "Entry is encrypted", r.name)
            }
            if (r.method != 0 && r.method != 8) {
                invalid("Entry uses compression method ${r.method}; only 0 (stored) and 8 (deflate) are supported", r.name)
            }
            need(b, r.localOffset, 30)
            if (u32(b, r.localOffset) != SIG_LOCAL) invalid("Bad local file header", r.name)
            val ln = u16(b, r.localOffset + 26)
            val le = u16(b, r.localOffset + 28)
            need(b, r.localOffset + 30, ln.toLong())
            // The central directory name governs (Section 4); a different local name is only reported.
            val localName = b.copyOfRange((r.localOffset + 30).toInt(), (r.localOffset + 30 + ln).toInt())
            r.localNameMatches = try {
                decodeName(localName, r.hasUtf8Flag) == r.name
            } catch (e: MdzException) {
                false
            }
            r.dataOffset = r.localOffset + 30 + ln + le
            if (r.dataOffset + r.compressedSize > cdOffset) invalid("Entry data runs into the central directory", r.name)
        }

        // Entries must not share or overlap data (the overlapping-entries ZIP bomb).
        val sorted = records.sortedBy { it.localOffset }
        for (k in 1 until sorted.size) {
            val prev = sorted[k - 1]
            if (prev.dataOffset + prev.compressedSize > sorted[k].localOffset) {
                invalid("Entries overlap in the archive", sorted[k].name)
            }
        }

        val zip64Needed = count >= 0xFFFF || cdOffset >= 0xFFFFFFFFL || cdSize >= 0xFFFFFFFFL ||
            records.any { it.size >= 0xFFFFFFFFL || it.compressedSize >= 0xFFFFFFFFL || it.localOffset >= 0xFFFFFFFFL }
        return ZipDirectory(records, usesZip64, zip64Needed)
    }

    /** Names are UTF-8 (Section 4), with or without the flag; CP437 is the fallback for legacy names. */
    private fun decodeName(bytes: ByteArray, utf8Flag: Boolean): String {
        try {
            return Text.decodeUtf8Strict(bytes)
        } catch (e: MdzException) {
            if (utf8Flag) invalid("Entry name is not valid UTF-8")
            return Cp437.decode(bytes)
        }
    }

    /**
     * Returns an entry's bytes. Decompression never produces more than the declared size: an
     * entry with more data fails with [MdzErrorCode.LIMIT_EXCEEDED] after one extra byte.
     */
    fun readData(b: ByteArray, r: ZipRecord): ByteArray {
        val size = r.size.toInt()
        val out = ByteArray(size)
        val start = r.dataOffset.toInt()
        val csize = r.compressedSize.toInt()
        if (r.method == 0) {
            if (r.compressedSize != r.size) invalid("Stored entry's sizes differ", r.name)
            System.arraycopy(b, start, out, 0, size)
        } else {
            val inflater = Inflater(true)
            try {
                inflater.setInput(b, start, csize)
                var pos = 0
                var dummyGiven = false
                var stalls = 0
                while (true) {
                    if (pos == size) {
                        if (inflater.finished()) break
                        // Output is full: any further byte means the entry is larger than declared.
                        val probe = ByteArray(1)
                        val extra = inflater.inflate(probe)
                        if (extra > 0) {
                            fail(MdzErrorCode.LIMIT_EXCEEDED, "Entry decompresses to more than its declared $size bytes", r.name)
                        }
                        if (inflater.finished()) break
                        if (inflater.needsInput() && !dummyGiven) {
                            inflater.setInput(ByteArray(1)); dummyGiven = true; continue
                        }
                        invalid("Compressed data is truncated", r.name)
                    }
                    val got = inflater.inflate(out, pos, size - pos)
                    pos += got
                    if (got > 0) { stalls = 0; continue }
                    if (inflater.finished()) {
                        if (pos != size) invalid("Entry is shorter than its declared size", r.name)
                        break
                    }
                    if (inflater.needsDictionary()) invalid("Compressed data needs a preset dictionary", r.name)
                    if (inflater.needsInput()) {
                        if (dummyGiven) invalid("Compressed data is truncated", r.name)
                        // zlib may want one extra byte to finish a raw stream.
                        inflater.setInput(ByteArray(1)); dummyGiven = true; continue
                    }
                    if (++stalls > 2) invalid("Compressed data is damaged", r.name)
                }
            } catch (e: DataFormatException) {
                throw MdzException(MdzErrorCode.ZIP_INVALID, "Compressed data is damaged: ${e.message}", r.name, cause = e)
            } finally {
                inflater.end()
            }
        }
        val crc = CRC32()
        crc.update(out, 0, out.size)
        if (crc.value != r.crc) invalid("CRC-32 check failed", r.name)
        return out
    }

    /** The raw compressed bytes of an entry, for copying it unchanged. */
    fun rawData(b: ByteArray, r: ZipRecord): ByteArray =
        b.copyOfRange(r.dataOffset.toInt(), (r.dataOffset + r.compressedSize).toInt())
}

/** IBM code page 437, the ZIP default for names without the UTF-8 flag. */
internal object Cp437 {
    private const val HIGH =
        "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

    fun decode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (x in bytes) {
            val c = x.toInt() and 0xff
            sb.append(if (c < 0x80) c.toChar() else HIGH[c - 0x80])
        }
        return sb.toString()
    }
}
