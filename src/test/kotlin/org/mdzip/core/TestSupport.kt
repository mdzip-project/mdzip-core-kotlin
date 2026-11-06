package org.mdzip.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixture files, read from the copy synced from the spec tag (never from a sibling checkout). */
object Fixtures {
    val dir = File("src/test/resources/fixtures")

    fun bytes(name: String): ByteArray = File(dir, name).readBytes()
    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
    fun json(name: String): JsonValue = JsonValue.parse(text(name))
    fun archive(name: String): MdzArchive = MdzArchive.open(bytes(name))

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

/** Collects FIXTURE-RESULT lines and writes them to build/fixture-results.txt. */
object Results {
    private val lines = sortedMapOf<String, String>()

    @Synchronized
    fun record(id: String, status: String, note: String = "") {
        val line = "FIXTURE-RESULT $id $status${if (note.isNotEmpty()) " — $note" else ""}"
        println(line)
        lines[id] = line
        File("build").mkdirs()
        File("build/fixture-results.txt").writeText(lines.values.joinToString("\n") + "\n")
    }

    /** Runs [block] and records the test as passed, or as failed with the assertion message. */
    fun run(id: String, note: String = "", block: () -> Unit) {
        try {
            val before = synchronized(this) { lines[id] }
            block()
            // Keep a more detailed line the test recorded itself.
            if (synchronized(this) { lines[id] } == before) record(id, "passed", note)
        } catch (e: Throwable) {
            record(id, "FAILED", e.message ?: e.toString())
            throw e
        }
    }

    fun notApplicable(id: String, reason: String) = record(id, "not-applicable", reason)
}

/** Builds test archives with the JDK's ZipOutputStream, independent of the library's writer. */
object TestZip {
    /** [entries]: name to content; a name ending in `/` is a directory. */
    fun build(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored || name.endsWith("/")) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun ofFiles(names: List<String>, manifestJson: String? = null): ByteArray = build(names.map { n ->
        n to when {
            n == "manifest.json" -> (manifestJson ?: "{}").toByteArray()
            n.endsWith("/") -> ByteArray(0)
            else -> "# $n\n".toByteArray()
        }
    })
}

/** An independent reader of ZIP central directory records, for checking what the library wrote. */
class RawZip(private val b: ByteArray) {
    class Record(val name: String, val flags: Int, val method: Int, val madeBy: Int, val external: Long, val extra: ByteArray)

    private fun u16(p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    private fun u32(p: Int) = u16(p).toLong() or (u16(p + 2).toLong() shl 16)

    val hasZip64Records: Boolean
    val records: List<Record>

    init {
        var eocd = b.size - 22
        while (u32(eocd) != 0x06054b50L) eocd--
        hasZip64Records = (0 until b.size - 3).any { u32(it) == 0x06064b50L || u32(it) == 0x07064b50L }
        val count = u16(eocd + 10)
        var p = u32(eocd + 16).toInt()
        val list = ArrayList<Record>()
        repeat(count) {
            check(u32(p) == 0x02014b50L)
            val n = u16(p + 28)
            val m = u16(p + 30)
            val k = u16(p + 32)
            list.add(Record(String(b, p + 46, n, Charsets.UTF_8), u16(p + 8), u16(p + 10), u16(p + 4), u32(p + 38),
                b.copyOfRange(p + 46 + n, p + 46 + n + m)))
            p += 46 + n + m + k
        }
        records = list
    }

    /** True when any record carries a ZIP64 extra field (header ID 0x0001). */
    fun hasZip64Extra(): Boolean = records.any { r ->
        var i = 0
        var found = false
        while (i + 4 <= r.extra.size) {
            val id = (r.extra[i].toInt() and 0xff) or ((r.extra[i + 1].toInt() and 0xff) shl 8)
            val len = (r.extra[i + 2].toInt() and 0xff) or ((r.extra[i + 3].toInt() and 0xff) shl 8)
            if (id == 1) found = true
            i += 4 + len
        }
        found
    }
}

/** Evaluates a JSON Pointer (RFC 6901). */
fun JsonValue.at(pointer: String): JsonValue? {
    if (pointer.isEmpty()) return this
    var cur: JsonValue? = this
    for (raw in pointer.substring(1).split('/')) {
        val key = raw.replace("~1", "/").replace("~0", "~")
        cur = when (cur) {
            is JsonObject -> cur[key]
            is JsonArray -> key.toIntOrNull()?.let { cur.items.getOrNull(it) }
            else -> null
        }
    }
    return cur
}

/** Writes sample archives for `mdz validate` to build/mdz-samples (and to $MDZIP_OUT when set). */
fun saveSample(name: String, bytes: ByteArray) {
    val dirs = listOfNotNull(File("build/mdz-samples"), System.getenv("MDZIP_OUT")?.let { File(it) })
    for (d in dirs) {
        d.mkdirs()
        File(d, name).writeBytes(bytes)
    }
}

inline fun <reified T : Throwable> catching(block: () -> Unit): T? = try {
    block(); null
} catch (e: Throwable) {
    if (e is T) e else throw e
}
