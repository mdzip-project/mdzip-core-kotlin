package org.mdzip.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater

/** An entry ready to write: its data is already compressed with [method]. */
internal class ZipOutEntry(
    val name: String,
    val method: Int,
    val crc: Long,
    val size: Long,
    val data: ByteArray,
    val dosTime: Int,
    val dosDate: Int,
    val externalAttrs: Long,
    val versionMadeBy: Int,
) {
    companion object {
        const val MADE_BY_UNIX = (3 shl 8) or 20
        const val ATTR_FILE = 0x81A4L shl 16 // regular file, rw-r--r--
        const val ATTR_DIR = (0x41EDL shl 16) or 0x10L // directory, rwxr-xr-x, plus the MS-DOS directory bit

        /** Compresses [bytes] with DEFLATE, or stores them when that isn't smaller. */
        fun file(name: String, bytes: ByteArray, time: DosTime): ZipOutEntry {
            val crc = CRC32().also { it.update(bytes, 0, bytes.size) }.value
            val deflated = deflate(bytes)
            val (method, data) = if (deflated.size < bytes.size) 8 to deflated else 0 to bytes
            return ZipOutEntry(name, method, crc, bytes.size.toLong(), data, time.time, time.date, ATTR_FILE, MADE_BY_UNIX)
        }

        fun directory(name: String, time: DosTime): ZipOutEntry =
            ZipOutEntry(name, 0, 0, 0, ByteArray(0), time.time, time.date, ATTR_DIR, MADE_BY_UNIX)

        private fun deflate(bytes: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream(maxOf(64, bytes.size / 2))
                val buf = ByteArray(16 * 1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }
    }
}

internal class DosTime(val time: Int, val date: Int) {
    companion object {
        fun now(): DosTime {
            val c = Calendar.getInstance()
            val year = maxOf(1980, c.get(Calendar.YEAR))
            val date = ((year - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
            val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
            return DosTime(time, date)
        }
    }
}

/**
 * Writes a ZIP archive: methods 0 and 8 only, no data descriptors, the UTF-8 flag on every
 * non-ASCII name, and ZIP64 records only when a count or offset doesn't fit without them.
 */
internal object ZipWriter {
    fun write(entries: List<ZipOutEntry>, target: OutputStream) {
        val out = CountingStream(target)
        val offsets = LongArray(entries.size)
        val names = entries.map { Text.utf8(it.name) }
        for ((k, e) in entries.withIndex()) {
            require(e.method == 0 || e.method == 8) { "Only methods 0 and 8 are written" }
            require(e.size < 0xFFFFFFFFL && e.data.size < 0xFFFFFFFFL)
            offsets[k] = out.count
            out.u32(0x04034b50L)
            out.u16(if (e.method == 8) 20 else 10)
            out.u16(flags(e))
            out.u16(e.method)
            out.u16(e.dosTime)
            out.u16(e.dosDate)
            out.u32(e.crc)
            out.u32(e.data.size.toLong())
            out.u32(e.size)
            out.u16(names[k].size)
            out.u16(0)
            out.write(names[k])
            out.write(e.data)
        }

        val cdOffset = out.count
        for ((k, e) in entries.withIndex()) {
            val bigOffset = offsets[k] >= 0xFFFFFFFFL
            out.u32(0x02014b50L)
            out.u16(e.versionMadeBy)
            out.u16(if (bigOffset) 45 else if (e.method == 8) 20 else 10)
            out.u16(flags(e))
            out.u16(e.method)
            out.u16(e.dosTime)
            out.u16(e.dosDate)
            out.u32(e.crc)
            out.u32(e.data.size.toLong())
            out.u32(e.size)
            out.u16(names[k].size)
            out.u16(if (bigOffset) 12 else 0)
            out.u16(0) // comment
            out.u16(0) // disk
            out.u16(0) // internal attributes
            out.u32(e.externalAttrs)
            out.u32(if (bigOffset) 0xFFFFFFFFL else offsets[k])
            out.write(names[k])
            if (bigOffset) {
                out.u16(0x0001); out.u16(8); out.u64(offsets[k])
            }
        }
        val cdSize = out.count - cdOffset
        val count = entries.size.toLong()

        if (count >= 0xFFFF || cdOffset >= 0xFFFFFFFFL || cdSize >= 0xFFFFFFFFL) {
            val z = out.count
            out.u32(0x06064b50L); out.u64(44); out.u16(45); out.u16(45)
            out.u32(0); out.u32(0); out.u64(count); out.u64(count); out.u64(cdSize); out.u64(cdOffset)
            out.u32(0x07064b50L); out.u32(0); out.u64(z); out.u32(1)
        }
        out.u32(0x06054b50L)
        out.u16(0); out.u16(0)
        out.u16(minOf(count, 0xFFFF).toInt()); out.u16(minOf(count, 0xFFFF).toInt())
        out.u32(minOf(cdSize, 0xFFFFFFFFL))
        out.u32(minOf(cdOffset, 0xFFFFFFFFL))
        out.u16(0)
        out.flush()
    }

    private fun flags(e: ZipOutEntry): Int = if (Text.isAscii(e.name)) 0 else 0x800

    private class CountingStream(private val out: OutputStream) {
        var count = 0L
        private val buf = ByteArray(8)

        fun write(b: ByteArray) { out.write(b); count += b.size }
        fun u16(v: Int) { buf[0] = v.toByte(); buf[1] = (v shr 8).toByte(); out.write(buf, 0, 2); count += 2 }
        fun u32(v: Long) { for (i in 0 until 4) buf[i] = (v shr (8 * i)).toByte(); out.write(buf, 0, 4); count += 4 }
        fun u64(v: Long) { for (i in 0 until 8) buf[i] = (v shr (8 * i)).toByte(); out.write(buf, 0, 8); count += 8 }
        fun flush() = out.flush()
    }
}
