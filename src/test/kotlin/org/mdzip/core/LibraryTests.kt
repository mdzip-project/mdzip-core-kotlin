package org.mdzip.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LibraryTests {
    private fun code(block: () -> Unit): MdzErrorCode =
        (catching<MdzException>(block) ?: fail("nothing was thrown")).code

    // --- JSON ---

    @Test
    fun jsonRejectsWhatRfc8259Rejects() {
        val bad = listOf("{\"a\":1,}", "[1,]", "01", "1.", ".5", "+1", "{'a':1}", "// c\n{}", "{\"a\":\"\u0001\"}",
            "\"\\x\"", "\"\\u12G4\"", "{} {}", "", "NaN", "{\"a\" 1}", "[", "\"abc", "tru", "-")
        for (s in bad) assertTrue(catching<JsonParseException> { JsonValue.parse(s) } != null, "accepted: $s")
        val deep = "[".repeat(300) + "]".repeat(300)
        assertTrue(catching<JsonParseException> { JsonValue.parse(deep) } != null, "deep nesting is refused")
    }

    @Test
    fun jsonRoundTripsValuesAndEquality() {
        val text = "{\"s\":\"a\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00\",\"n\":[0,-1.5e3,3.5],\"t\":true,\"f\":false,\"z\":null,\"o\":{}}"
        val v = JsonValue.parse(text) as JsonObject
        assertEquals("a\"\\/\b\u000c\n\r\té\uD83D\uDE00", v.getString("s"))
        assertEquals(v, JsonValue.parse(v.toJson()))
        assertEquals(v, JsonValue.parse(v.toPrettyJson()))
        assertEquals(JsonValue.parse("3.5"), JsonValue.parse("3.50"))
        assertEquals(JsonValue.parse("{\"a\":1,\"b\":2}"), JsonValue.parse("{\"b\":2,\"a\":1}"))
        assertEquals("1.0E20", JsonNumber.of(1e20).text)
        assertEquals("\"\\ud800\"", JsonString("\uD800").toJson(), "lone surrogates are escaped")
    }

    // --- ZIP reading ---

    @Test
    fun cp437MatchesTheJdkTable() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(String(all, Charset.forName("IBM437")), Cp437.decode(all))
    }

    @Test
    fun rejectsInputThatIsNotAZip() {
        assertEquals(MdzErrorCode.ZIP_INVALID, code { MdzArchive.open(ByteArray(0)) })
        assertEquals(MdzErrorCode.ZIP_INVALID, code { MdzArchive.open("not a zip at all, just text".toByteArray()) })
        assertEquals(MdzErrorCode.ZIP_INVALID, code { MdzArchive.open(ByteArray(100)) })
    }

    /** Offset of the central directory record for [name]. */
    private fun cdRecord(b: ByteArray, name: String): Int {
        val n = name.toByteArray()
        for (p in 0 until b.size - 46 - n.size) {
            if (b[p] == 0x50.toByte() && b[p + 1] == 0x4b.toByte() && b[p + 2] == 1.toByte() && b[p + 3] == 2.toByte() &&
                b.copyOfRange(p + 46, p + 46 + n.size).contentEquals(n)) return p
        }
        error("no record for $name")
    }

    private fun putU32(b: ByteArray, p: Int, v: Long) { for (i in 0 until 4) b[p + i] = (v shr (8 * i)).toByte() }
    private fun u32(b: ByteArray, p: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((b[p + i].toLong() and 0xff) shl (8 * i)) }

    private fun sample(): ByteArray = TestZip.build(listOf("index.md" to "# Hello\n".repeat(200).toByteArray()))

    @Test
    fun damagedDataFailsWithAStableCode() {
        val corrupt = sample()
        val p = cdRecord(corrupt, "index.md")
        val local = u32(corrupt, p + 42).toInt()
        val data = local + 30 + 8 + (u32(corrupt, local + 28) and 0xffff).toInt() // header, name, extra
        corrupt[data + 5] = (corrupt[data + 5].toInt() xor 0x55).toByte()
        assertEquals(MdzErrorCode.ZIP_INVALID, MdzArchive.open(corrupt).let { a -> code { a.readBytes("index.md") } })

        val longer = sample() // declares more bytes than the data holds
        val q = cdRecord(longer, "index.md")
        putU32(longer, q + 24, u32(longer, q + 24) + 10)
        assertEquals(MdzErrorCode.ZIP_INVALID, MdzArchive.open(longer).let { a -> code { a.readBytes("index.md") } })

        val shorter = sample() // declares fewer bytes: never returns more than declared
        val r = cdRecord(shorter, "index.md")
        putU32(shorter, r + 24, 100)
        assertEquals(MdzErrorCode.LIMIT_EXCEEDED, MdzArchive.open(shorter).let { a -> code { a.readBytes("index.md") } })

        val truncated = sample() // compressed size cut short
        val s = cdRecord(truncated, "index.md")
        putU32(truncated, s + 20, 10)
        assertEquals(MdzErrorCode.ZIP_INVALID, MdzArchive.open(truncated).let { a -> code { a.readBytes("index.md") } })

        val encrypted = sample()
        val t = cdRecord(encrypted, "index.md")
        encrypted[t + 8] = (encrypted[t + 8].toInt() or 1).toByte()
        assertEquals(MdzErrorCode.ZIP_ENCRYPTED, code { MdzArchive.open(encrypted) })
    }

    @Test
    fun overlappingEntriesAreRejectedEvenUnderTheLimits() {
        val b = TestZip.build(listOf("index.md" to "# a\n".toByteArray(), "b.md" to "# b\n".toByteArray()), stored = true)
        val first = cdRecord(b, "index.md")
        val second = cdRecord(b, "b.md")
        putU32(b, second + 42, u32(b, first + 42)) // b.md now points at index.md's data
        assertEquals(MdzErrorCode.ZIP_INVALID, code { MdzArchive.open(b) })
    }

    @Test
    fun readsLargeAndBinaryEntriesExactly() {
        val rnd = Random(42)
        val noise = ByteArray(3 shl 20).also { rnd.nextBytes(it) }
        val text = ("All work and no play makes Jack a dull boy.\n".repeat(80_000)).toByteArray()
        val bytes = MdzBuilder().addText("index.md", "# Big\n").addBytes("noise.bin", noise).addBytes("text.txt", text).toByteArray()
        val a = MdzArchive.open(bytes)
        assertContentEquals(noise, a.readBytes("noise.bin"))
        assertContentEquals(text, a.readBytes("text.txt"))
        assertEquals(0, a.entry("noise.bin")!!.method, "incompressible data is stored")
        assertEquals(8, a.entry("text.txt")!!.method)
        // And entries deflated by the JDK's own writer.
        val jdk = TestZip.build(listOf("index.md" to "# x".toByteArray(), "text.txt" to text, "empty.txt" to ByteArray(0)))
        assertContentEquals(text, MdzArchive.open(jdk).readBytes("text.txt"))
        assertEquals(0, MdzArchive.open(jdk).readBytes("empty.txt").size)
    }

    @Test
    fun readTextIsStrictUtf8() {
        val a = MdzArchive.open(TestZip.build(listOf("index.md" to "# ok".toByteArray(), "bad.md" to byteArrayOf(0x23, 0xC3.toByte(), 0x28),
            "bom.md" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41))))
        assertEquals(MdzErrorCode.TEXT_DECODING, code { a.readText("bad.md") })
        assertEquals("A", a.readText("bom.md"))
        assertEquals(MdzErrorCode.ENTRY_NOT_FOUND, code { a.readText("missing.md") })
        assertTrue(a.validate().hasCode(MdzErrorCode.TEXT_DECODING))
    }

    @Test
    fun opensStreamsAndFiles() {
        val bytes = Fixtures.bytes("with-manifest.mdz")
        assertEquals("index.md", MdzArchive.open(ByteArrayInputStream(bytes)).entryPoint())
        assertEquals("index.md", MdzArchive.open(File(Fixtures.dir, "with-manifest.mdz")).entryPoint())
        assertEquals(MdzErrorCode.IO, code { MdzArchive.open(File("does-not-exist.mdz")) })
    }

    @Test
    fun nearMatchIsAHintNeverAMatch() {
        val a = MdzArchive.open(TestZip.ofFiles(listOf("index.md", "Images/Logo.png")))
        assertFalse("images/logo.png" in a)
        assertEquals("Images/Logo.png", a.nearMatch("images/logo.png"))
        assertTrue(a.resolveLink("images/logo.png", "index.md") is MdzLink.Unresolved)
        val e = catching<MdzException> { a.readBytes("images/logo.png") }!!
        assertTrue(e.message!!.contains("did you mean"))
    }

    @Test
    fun caseCollisionsAndNonNfcNamesAreWarnings() {
        val a = MdzArchive.open(TestZip.ofFiles(listOf("index.md", "Index.md", "cafe\u0301.md")))
        val r = a.validate()
        assertTrue(r.isValid, r.toString())
        assertTrue(r.hasCode("WARN_CASE_COLLISION"))
        assertTrue(r.hasCode("WARN_PATH_NOT_NFC"))
    }

    // --- Writing ---

    @Test
    fun zip64OnlyWhenTheEntryCountNeedsIt() {
        val small = MdzBuilder().addText("index.md", "# x\n").toByteArray()
        assertFalse(RawZip(small).hasZip64Records)

        val b = MdzBuilder().addText("index.md", "# Many\n")
        for (i in 0 until 70_000) b.addBytes("d/$i.txt", ByteArray(0))
        val many = b.toByteArray()
        assertTrue(RawZip(many).hasZip64Records, "more than 65,535 entries need ZIP64")
        assertEquals(MdzErrorCode.LIMIT_EXCEEDED, code { MdzArchive.open(many) }, "over the default entry limit")
        val a = MdzArchive.open(many, Limits(maxEntries = 100_000))
        assertEquals(70_002, a.entries.size)
        assertFalse(a.validate().hasCode("WARN_ZIP64_UNNEEDED"))
    }

    @Test
    fun builderChecksWhatItWrites() {
        assertEquals(MdzErrorCode.ENTRYPOINT_UNRESOLVED, code { MdzBuilder().addText("a.md", "").addText("b.md", "").toByteArray() })
        assertEquals("b.md", MdzArchive.open(MdzBuilder().addText("a.md", "").addText("b.md", "").setEntryPoint("./b.md").toByteArray()).entryPoint())
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { MdzBuilder().addText("a.md", "").setEntryPoint("c.md").toByteArray() })
        assertEquals(MdzErrorCode.ENTRYPOINT_INVALID, code { MdzBuilder().setEntryPoint("../a.md") })
        assertEquals(MdzErrorCode.PATH_INVALID, code { MdzBuilder().addText("../a.md", "") })
        assertEquals(MdzErrorCode.PATH_CONFLICT, code { MdzBuilder().addText("index.md", "").addText("docs", "").addText("docs/a.md", "").toByteArray() })
        assertEquals(MdzErrorCode.MANIFEST_INVALID, code { MdzBuilder().addText("index.md", "").setTitle("").toByteArray() })
        assertEquals(MdzErrorCode.INVALID_ARGUMENT, code { MdzBuilder().addText("manifest.json", "{}") })
        assertEquals(MdzErrorCode.ENTRY_NOT_FOUND, code { MdzBuilder().addText("index.md", "").setCover("x.png").toByteArray() })
        // Unknown metadata goes through untouched.
        val b = MdzBuilder().addText("index.md", "# T\n")
        b.manifest.put("x-app", JsonObject().put("state", JsonArray(listOf(JsonNumber.of(1), JsonNull))))
        val m = MdzArchive.open(b.toByteArray()).manifest()!!
        assertEquals(b.manifest["x-app"], m.json["x-app"])
    }

    @Test
    fun editorKeepsTheEntryPointAndRejectsRemovingIt() {
        val source = Fixtures.archive("round-trip.mdz")
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { source.edit().also { it.remove("index.md") }.toByteArray() })
        val moved = source.edit().also {
            it.setText("start.md", "# Start\n")
            it.remove("index.md")
            it.setEntryPoint("start.md")
        }.toByteArray()
        assertEquals("start.md", MdzArchive.open(moved).entryPoint())
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { source.edit().setEntryPoint("nope.md").toByteArray() })
        assertEquals(MdzErrorCode.MANIFEST_INVALID, code { source.edit().also { it.manifest.put("mode", JsonNumber.of(3)) }.toByteArray() })
        assertEquals(MdzErrorCode.MANIFEST_INVALID, code { source.edit().setTitle("").toByteArray() })
        assertEquals(MdzErrorCode.PATH_CONFLICT, code { source.edit().setText("index.md/x.md", "").toByteArray() })
    }

    @Test
    fun editorReplacesInPlaceAndKeepsStoredNames() {
        val src = MdzArchive.open(TestZip.build(listOf("index.md" to "# a".toByteArray(), "cafe\u0301.md" to "old".toByteArray(), "empty/" to ByteArray(0))))
        val out = MdzArchive.open(src.edit().setText("caf\u00e9.md", "new").toByteArray())
        assertEquals(listOf("manifest.json", "index.md", "cafe\u0301.md", "empty/"), out.entries.map { it.path })
        assertEquals("new", out.readText("caf\u00e9.md"))
        assertEquals(listOf("empty/"), out.directories)
    }

    @Test
    fun saveWithoutChangesKeepsEveryEntryByteForByte() {
        val src = Fixtures.archive("with-manifest.mdz")
        val out = MdzArchive.open(src.edit().toByteArray())
        for (path in src.files) assertContentEquals(src.readBytes(path), out.readBytes(path), path)
    }

    @Test
    fun fileWritesAreAtomic() {
        val dir = File("build/tmp/atomic").also { it.deleteRecursively(); it.mkdirs() }
        val target = File(dir, "doc.mdz")
        target.writeBytes(Fixtures.bytes("round-trip.mdz"))
        val before = target.readBytes()

        // A failed save leaves the file exactly as it was, with no temporary files behind.
        val failing = MdzArchive.open(target).edit().also { it.remove("index.md") }
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { failing.writeTo(target) })
        assertContentEquals(before, target.readBytes())
        assertEquals(listOf("doc.mdz"), dir.list()!!.toList())

        // A successful save replaces the existing file (on Windows too).
        MdzArchive.open(target).edit().setTitle("Saved").writeTo(target)
        assertEquals("Saved", MdzArchive.open(target).manifest()!!.title)
        assertEquals(listOf("doc.mdz"), dir.list()!!.toList())

        // Writing into a folder that doesn't exist fails with ERR_IO and creates nothing.
        assertEquals(MdzErrorCode.IO, code { MdzBuilder().addText("index.md", "#").writeTo(File(dir, "missing/x.mdz")) })
    }

    @Test
    fun manifestLessEditCanSkipTheManifest() {
        val e = Fixtures.archive("minimal.mdz").edit()
        e.addManifestIfMissing = false
        val out = MdzArchive.open(e.toByteArray())
        assertFalse(out.hasManifest)
        assertNull(out.manifest())
    }

    @Test
    fun linksKeepFragmentsAndNeverClamp() {
        val a = MdzArchive.open(TestZip.ofFiles(listOf("index.md", "docs/a b.md")))
        val l = a.resolveLink("docs/a%20b.md#part", "index.md")
        assertTrue(l is MdzLink.Internal && l.path == "docs/a b.md" && l.fragment == "part")
        assertEquals(MdzLink.Reason.UNSUPPORTED, (a.resolveLink("../index.md", "index.md") as MdzLink.Unresolved).reason)
        assertTrue(a.resolveLink("mailto:a@example.com", "index.md") is MdzLink.External)
        assertNotNull(a.resolveLink("docs/../index.md", "index.md") as? MdzLink.Internal)
    }

    @Test
    fun libraryVersionMatchesTheBuild() {
        val props = File("gradle.properties").readLines().first { it.startsWith("VERSION_NAME=") }.substringAfter("=")
        assertEquals(props, MdzLibrary.VERSION)
    }
}
