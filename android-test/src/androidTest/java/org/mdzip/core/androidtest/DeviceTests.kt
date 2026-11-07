package org.mdzip.core.androidtest

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mdzip.core.JsonArray
import org.mdzip.core.JsonBoolean
import org.mdzip.core.JsonNumber
import org.mdzip.core.JsonObject
import org.mdzip.core.JsonString
import org.mdzip.core.JsonValue
import org.mdzip.core.MdzArchive
import org.mdzip.core.MdzBuilder
import org.mdzip.core.MdzErrorCode
import org.mdzip.core.MdzException
import org.mdzip.core.MdzLink
import org.mdzip.core.MdzManifest
import org.mdzip.core.MdzMode
import org.mdzip.core.MdzPaths
import org.mdzip.core.SymlinkPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.zip.CRC32

/**
 * The library's checks on a real Android device or emulator: the spec fixtures (v1.2.1) and the
 * platform pieces that differ between Android versions (zlib, Unicode normalization, regular
 * expressions, date formatting, file renames, locales).
 */
@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun asset(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }
    private fun archive(name: String): MdzArchive = instrumentation.context.assets.open(name).use { MdzArchive.open(it) }
    private fun json(name: String): JsonValue = JsonValue.parse(String(asset(name), Charsets.UTF_8))
    private fun objects(v: JsonValue?): List<JsonObject> = (v as JsonArray).items.map { it as JsonObject }
    private fun strings(v: JsonValue?): List<String> = (v as JsonArray).items.map { (it as JsonString).value }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun code(block: () -> Unit): MdzErrorCode? = try {
        block(); null
    } catch (e: MdzException) {
        e.code
    }

    /** A minimal stored ZIP writer for test input (the platform's ZipOutputStream varies by version). */
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
        fun u32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xff).toInt()) }
        for ((name, data) in entries) {
            val n = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(data) }.value
            val flags = if (name.all { it.code < 0x80 }) 0 else 0x800
            val offset = out.size().toLong()
            u32(out, 0x04034b50); u16(out, 10); u16(out, flags); u16(out, 0); u16(out, 0); u16(out, 0x21)
            u32(out, crc); u32(out, data.size.toLong()); u32(out, data.size.toLong()); u16(out, n.size); u16(out, 0)
            out.write(n); out.write(data)
            u32(central, 0x02014b50); u16(central, 20); u16(central, 10); u16(central, flags); u16(central, 0)
            u16(central, 0); u16(central, 0x21); u32(central, crc); u32(central, data.size.toLong())
            u32(central, data.size.toLong()); u16(central, n.size); u16(central, 0); u16(central, 0); u16(central, 0)
            u16(central, 0); u32(central, 0); u32(central, offset); central.write(n)
        }
        val cdOffset = out.size().toLong()
        val cd = central.toByteArray()
        out.write(cd)
        u32(out, 0x06054b50); u16(out, 0); u16(out, 0); u16(out, entries.size); u16(out, entries.size)
        u32(out, cd.size.toLong()); u32(out, cdOffset); u16(out, 0)
        return out.toByteArray()
    }

    private fun zipOf(names: List<String>, manifestJson: String? = null): ByteArray = zip(names.map { n ->
        n to when {
            n == "manifest.json" -> (manifestJson ?: "{}").toByteArray()
            n.endsWith("/") -> ByteArray(0)
            else -> "# $n\n".toByteArray()
        }
    })

    @Test
    fun device() {
        Log.i(TAG, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
    }

    @Test
    fun fixturesArriveUnchanged() {
        val files = (json("SPEC-FIXTURES.json") as JsonObject).getObject("files")!!
        for (name in files.keys) assertEquals(name, files.getString(name), sha256(asset(name)))
    }

    @Test
    fun consumerArchives() {
        assertEquals("index.md", archive("minimal.mdz").entryPoint())                          // T-001
        assertEquals("index.md", archive("with-manifest.mdz").entryPoint())                    // T-002
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { archive("invalid-missing-index.mdz").entryPoint() }) // T-003
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { archive("invalid-bad-entrypoint.mdz").entryPoint() }) // T-004
        assertTrue(archive("line-endings-crlf.mdz").readText("index.md").contains("\r\n"))   // T-005
        val minimal = archive("minimal.mdz").validate()                                         // T-007
        assertTrue(minimal.toString(), minimal.isValid && minimal.warnings.isEmpty())
        assertEquals(MdzErrorCode.ZIP_INVALID, code { archive("unsupported-compression.mdz") }) // T-014
        val project = archive("project-mode.mdz")                                               // T-015
        assertEquals(MdzMode.PROJECT, project.mode())
        assertEquals(setOf("index.md", "chapters/one.md", "chapters/two.md"), project.documents().toSet())
        val links = archive("symlink-entries.mdz")                                              // T-017
        assertEquals("index.md", links.entryPoint())
        assertEquals(setOf("shortcut.md", "linked", "escape.md"), links.specialEntries.map { it.path }.toSet())
        assertEquals(MdzErrorCode.SPECIAL_ENTRY, code { links.readBytes("shortcut.md") })
        assertTrue(links.resolveLink("linked/page.md", "index.md") is MdzLink.Unresolved)
    }

    @Test
    fun zipBombsStopEarly() { // T-018
        val forged = archive("limit-forged-size.mdz")
        forged.readText("index.md")
        assertTrue(code { forged.readBytes("data/blob.bin") } in setOf(MdzErrorCode.LIMIT_EXCEEDED, MdzErrorCode.ZIP_INVALID))
        assertEquals(MdzErrorCode.LIMIT_EXCEEDED, code { archive("limit-high-ratio.mdz") })
        assertTrue(code { archive("limit-overlapping-entries.mdz") } in setOf(MdzErrorCode.LIMIT_EXCEEDED, MdzErrorCode.ZIP_INVALID))
    }

    @Test
    fun entryPointCases() { // T-006, T-008
        for (c in objects(json("entrypoint-cases.json"))) {
            val name = c.getString("name")
            val a = MdzArchive.open(zipOf(strings(c["files"]), c.getString("manifestJson") ?: (c["manifest"] as? JsonObject)?.toJson()))
            val result: Any = try { a.entryPoint() } catch (e: MdzException) { e.code }
            val expected: Any = c.getString("expectedError")?.let { MdzErrorCode.fromCode(it)!! }
                ?: c.getString("expectedEntryPoint") ?: MdzErrorCode.ENTRYPOINT_UNRESOLVED
            assertEquals(name, expected, result)
        }
    }

    @Test
    fun turkishLocale() { // T-010
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("notes.md", MdzArchive.open(zipOf(listOf("LICENSE.md", "notes.md"))).entryPoint())
            assertTrue(MdzPaths.isSupportFile("license.MARKDOWN"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun pathMatchingAndNames() { // T-009, T-013
        for (c in objects(json("path-matching-cases.json"))) {
            val a = MdzArchive.open(zipOf(listOf(c.getString("entry")!!)))
            assertEquals(c.getString("name"), (c["matches"] as JsonBoolean).value, c.getString("lookup")!! in a)
        }
        for (c in objects((json("entry-set-cases.json") as JsonObject)["cases"])) {
            val result = code { MdzArchive.open(zipOf(strings(c["entries"]))) }
            assertEquals(c.getString("name"), c.getString("errorCode"), result?.code)
        }
        for (c in objects(json("path-validation-cases.json"))) {
            assertEquals(c.getString("name"), c.getString("errorCode"), MdzPaths.check(c.getString("path")!!)?.code)
        }
    }

    @Test
    fun manifestCases() { // T-012
        for (c in objects((json("manifest-cases.json") as JsonObject)["cases"])) {
            val expected = c.getObject("expected")!!
            val result = try { MdzManifest.parse(c.getString("manifestJson")!!); null } catch (e: MdzException) { e.code.code }
            assertEquals(c.getString("name"), expected.getString("errorCode"), result)
        }
    }

    @Test
    fun linkCases() { // T-011, links that start inside an archive
        val doc = json("link-resolution-cases.json") as JsonObject
        val entries = strings(doc.getObject("host")!!.getObject("archives")!!.getObject("MyVault/Project.mdz")!!["entries"])
        val a = MdzArchive.open(zipOf(entries))
        for (c in objects(doc["cases"])) {
            val from = c.getObject("from")!!.getString("path") ?: continue
            val expected = c.getObject("expected")!!
            val r = a.resolveLink(c.getString("link")!!, from)
            val name = c.getString("name")
            when (expected.getString("kind")) {
                "internal" -> {
                    assertTrue(name, r is MdzLink.Internal)
                    assertEquals(name, expected.getString("entry"), (r as MdzLink.Internal).path)
                    assertEquals(name, expected.getString("fragment"), r.fragment)
                }
                "url" -> assertTrue(name, r is MdzLink.External)
                else -> assertTrue(name, r is MdzLink.Unresolved)
            }
        }
    }

    @Test
    fun roundTripKeepsEverything() { // P-004, P-005
        val exp = json("round-trip-expectations.json") as JsonObject
        var bytes = asset("round-trip.mdz")
        repeat(2) { round ->
            val e = MdzArchive.open(bytes).edit()
            e.setTitle("Edited title")
            e.setText("index.md", MdzArchive.open(bytes).readText("index.md") + "Appended ${round + 1}.\n")
            e.setBytes("images/added.png", byteArrayOf(1, 2, 3))
            bytes = e.toByteArray()
        }
        val a = MdzArchive.open(bytes)
        assertEquals("index.md", a.entryPoint())
        assertEquals("Edited title", a.manifest()!!.title)
        val m = a.manifest()!!.json
        for (v in objects(exp["preservedManifestValues"])) {
            var cur: JsonValue? = m
            for (key in v.getString("pointer")!!.substring(1).split('/')) cur = (cur as JsonObject)[key]
            assertEquals(v.getString("pointer"), v["value"], cur)
        }
        for (pe in objects(exp["preservedEntries"])) {
            val path = pe.getString("path")!!
            if (path == "index.md") continue
            val data = a.readBytes(path)
            assertEquals(path, (pe["bytes"] as JsonNumber).text.toInt(), data.size)
            assertEquals(path, pe.getString("sha256"), sha256(data))
        }
    }

    @Test
    fun createAndReadLargeAndUnicodeContent() { // P-001, P-007, and the platform's Deflater/Inflater
        val noise = ByteArray(3 shl 20).also { Random(7).nextBytes(it) }
        val text = "All work and no play makes Jack a dull boy.\n".repeat(80_000).toByteArray()
        val bytes = MdzBuilder()
            .setApplication("android-test", "1")
            .addText("index.md", "# Unicode ünïcødé\r\n")
            .addText("café/文档.md", "# 文档\n")
            .addBytes("noise.bin", noise)
            .addBytes("text.txt", text)
            .toByteArray()
        val a = MdzArchive.open(bytes)
        assertEquals("index.md", a.entryPoint())
        assertEquals("Unicode ünïcødé", a.manifest()!!.title)
        assertEquals("# Unicode ünïcødé\n", a.readText("index.md"))
        assertTrue("café/文档.md" in a.files) // stored in NFC
        assertArrayEquals(noise, a.readBytes("noise.bin"))
        assertArrayEquals(text, a.readBytes("text.txt"))
        val created = a.manifest()!!.created!!.`when`
        assertTrue(created, Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(created))
        val report = a.validate()
        assertTrue(report.toString(), report.isValid && report.warnings.isEmpty())
    }

    @Test
    fun symlinkPolicies() { // P-008
        val source = archive("symlink-entries.mdz")
        assertEquals(3, MdzArchive.open(source.edit().setText("notes.md", "# x\n").toByteArray()).specialEntries.size)
        val files = source.edit().apply { symlinkPolicy = SymlinkPolicy.STORE_AS_FILE }.setText("notes.md", "# x\n").toByteArray()
        assertEquals("notes.md", MdzArchive.open(files).readText("shortcut.md"))
        val removed = source.edit().apply { symlinkPolicy = SymlinkPolicy.REMOVE }.setText("notes.md", "# x\n").toByteArray()
        assertNull(MdzArchive.open(removed).entry("linked"))
    }

    @Test
    fun fileSavesReplaceAtomically() {
        val dir = File(instrumentation.targetContext.cacheDir, "mdz-test").also { it.deleteRecursively(); it.mkdirs() }
        val target = File(dir, "doc.mdz")
        target.writeBytes(asset("round-trip.mdz"))
        val before = target.readBytes()
        val failing = MdzArchive.open(target).edit().also { it.remove("index.md") }
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, code { failing.writeTo(target) })
        assertArrayEquals(before, target.readBytes())
        MdzArchive.open(target).edit().setTitle("Saved").writeTo(target)
        assertEquals("Saved", MdzArchive.open(target).manifest()!!.title)
        assertEquals(listOf("doc.mdz"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test
    fun errorsHaveCodes() {
        assertEquals(MdzErrorCode.ZIP_INVALID, code { MdzArchive.open(byteArrayOf(1, 2, 3)) })
        assertNotNull(MdzErrorCode.fromCode("ERR_LIMIT_EXCEEDED"))
        assertFalse(MdzPaths.matches("index.md", "Index.md"))
        try {
            MdzArchive.open(zipOf(listOf("../escape.md")))
            fail("traversal accepted")
        } catch (e: MdzException) {
            assertEquals(MdzErrorCode.PATH_TRAVERSAL, e.detail)
        }
    }

    private companion object {
        const val TAG = "MdzDeviceTest"
    }
}
