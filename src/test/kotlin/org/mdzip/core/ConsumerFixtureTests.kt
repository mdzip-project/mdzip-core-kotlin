package org.mdzip.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The specification's consumer tests (fixtures/README.md), run through the public API. */
class ConsumerFixtureTests {
    private fun expectCode(code: MdzErrorCode, block: () -> Unit): MdzException {
        val e = catching<MdzException>(block) ?: fail("expected $code, but nothing was thrown")
        assertEquals(code, e.code, "wrong error code: ${e.message}")
        return e
    }

    @Test
    fun fixturesMatchTheirMarker() {
        val marker = Fixtures.json("SPEC-FIXTURES.json") as JsonObject
        assertEquals("v1.2.1", marker.getString("ref"))
        val files = marker.getObject("files")!!
        for (name in files.keys) {
            assertEquals(files.getString(name), Fixtures.sha256(Fixtures.bytes(name)), "$name differs from the spec tag")
        }
    }

    @Test
    fun t001MinimalPackage() = Results.run("T-001") {
        val a = Fixtures.archive("minimal.mdz")
        assertEquals("index.md", a.entryPoint())
        assertTrue(a.readText("index.md").isNotEmpty())
    }

    @Test
    fun t002WithManifest() = Results.run("T-002") {
        val a = Fixtures.archive("with-manifest.mdz")
        val m = assertNotNull(a.manifest())
        assertEquals(MdzPaths.normalizeEntryPoint(m.entryPoint!!), a.entryPoint())
        assertEquals("index.md", a.entryPoint())
    }

    @Test
    fun t003MissingIndexDoesNotFallBack() = Results.run("T-003") {
        val a = Fixtures.archive("invalid-missing-index.mdz")
        expectCode(MdzErrorCode.ENTRYPOINT_MISSING) { a.entryPoint() }
        assertTrue(a.validate().hasCode(MdzErrorCode.ENTRYPOINT_MISSING))
    }

    @Test
    fun t004BadEntryPointDoesNotFallBack() = Results.run("T-004") {
        val a = Fixtures.archive("invalid-bad-entrypoint.mdz")
        expectCode(MdzErrorCode.ENTRYPOINT_MISSING) { a.entryPoint() }
    }

    @Test
    fun t005Crlf() = Results.run("T-005") {
        val a = Fixtures.archive("line-endings-crlf.mdz")
        val text = a.readText(a.entryPoint())
        assertTrue(text.contains("\r\n"), "CRLF text is read as stored")
        assertTrue(a.validate().isValid)
    }

    private fun runEntryPointCases(filter: (JsonObject) -> Boolean): Int {
        var n = 0
        for (c in (Fixtures.json("entrypoint-cases.json") as JsonArray).items.map { it as JsonObject }.filter(filter)) {
            val name = c.getString("name")
            val files = (c["files"] as JsonArray).items.map { (it as JsonString).value }
            val manifestJson = c.getString("manifestJson") ?: (c["manifest"] as? JsonObject)?.toJson()
            val a = MdzArchive.open(TestZip.ofFiles(files, manifestJson))
            val expectedError = c.getString("expectedError")
            val expected = c.getString("expectedEntryPoint")
            val result = try { a.entryPoint() } catch (e: MdzException) { e }
            when {
                expectedError != null ->
                    assertEquals(expectedError, (result as? MdzException)?.code?.code, "$name: got $result")
                expected == null ->
                    assertTrue(result is MdzException && result.code == MdzErrorCode.ENTRYPOINT_UNRESOLVED, "$name: got $result")
                else -> assertEquals(expected, result, name)
            }
            n++
        }
        return n
    }

    @Test
    fun t006SupportFiles() {
        var n = 0
        Results.run("T-006") { n = runEntryPointCases { it["expectedError"] == null } }
        println("T-006 ran $n entrypoint-cases.json cases without expectedError")
    }

    @Test
    fun t007NoManifest() = Results.run("T-007") {
        val a = Fixtures.archive("minimal.mdz")
        assertFalse(a.hasManifest)
        assertNull(a.manifest())
        assertEquals(MdzMode.DOCUMENT, a.mode())
        val report = a.validate()
        assertTrue(report.isValid, report.toString())
        assertTrue(report.warnings.isEmpty(), "a missing manifest is not a warning: $report")
        assertTrue(report.hasCode("NOTE_NO_MANIFEST"))
    }

    @Test
    fun t008InvalidVersusMissing() = Results.run("T-008") { runEntryPointCases { it["expectedError"] != null } }

    @Test
    fun t009PathMatching() = Results.run("T-009") {
        for (c in (Fixtures.json("path-matching-cases.json") as JsonArray).items.map { it as JsonObject }) {
            val entry = c.getString("entry")!!
            val lookup = c.getString("lookup")!!
            val expected = (c["matches"] as JsonBoolean).value
            assertEquals(expected, MdzPaths.matches(entry, lookup), c.getString("name"))
            val a = MdzArchive.open(TestZip.ofFiles(listOf(entry)))
            assertEquals(expected, lookup in a, "archive lookup: ${c.getString("name")}")
            if (expected) assertEquals(entry, a.entry(lookup)!!.path, "returns the stored path")
            else assertNull(a.entry(lookup))
        }
    }

    @Test
    fun t010TurkishLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            Results.run("T-010", "default locale set to tr-TR") {
                val a = MdzArchive.open(TestZip.ofFiles(listOf("LICENSE.md", "notes.md")))
                assertEquals("notes.md", a.entryPoint())
                assertTrue(MdzPaths.isSupportFile("LICENSE.md"))
                assertTrue(MdzPaths.isSupportFile("license.MARKDOWN"))
                assertTrue(MdzPaths.isMarkdown("NOTES.MD"))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun t011LinkResolution() {
        val doc = Fixtures.json("link-resolution-cases.json") as JsonObject
        val archives = doc.getObject("host")!!.getObject("archives")!!
        val cases = (doc["cases"] as JsonArray).items.map { it as JsonObject }
        var ran = 0
        var skipped = 0
        Results.run("T-011") {
            for (c in cases) {
                val from = c.getObject("from")!!
                val archivePath = from.getString("archive")
                if (archivePath == null) { skipped++; continue } // starts from a host note: needs host resolution
                val entries = (archives.getObject(archivePath)!!["entries"] as JsonArray).items.map { (it as JsonString).value }
                val a = MdzArchive.open(TestZip.ofFiles(entries))
                val link = c.getString("link")!!
                val result = a.resolveLink(link, from.getString("path")!!)
                val expected = c.getObject("expected")!!
                val name = c.getString("name")
                when (expected.getString("kind")) {
                    "internal" -> {
                        assertTrue(result is MdzLink.Internal, "$name: got $result")
                        assertEquals(expected.getString("entry"), result.path, name)
                        assertEquals(expected.getString("fragment"), result.fragment, name)
                    }
                    "url" -> assertTrue(result is MdzLink.External, "$name: got $result")
                    // Without host support, links outside and into archives must be unresolved.
                    "unresolved", "host", "archive" -> assertTrue(result is MdzLink.Unresolved, "$name: got $result")
                    else -> fail("unknown kind in $name")
                }
                ran++
            }
        }
        Results.record("T-011", "passed", "$ran cases from archive documents run with outside/into-archive links unsupported (must be unresolved); $skipped cases starting from a host note not applicable")
    }

    @Test
    fun t012ManifestValidation() = Results.run("T-012") {
        val doc = Fixtures.json("manifest-cases.json") as JsonObject
        for (c in (doc["cases"] as JsonArray).items.map { it as JsonObject }) {
            val name = c.getString("name")
            val expected = c.getObject("expected")!!
            val text = c.getString("manifestJson")!!
            if ((expected["valid"] as JsonBoolean).value) {
                val m = try { MdzManifest.parse(text) } catch (e: MdzException) { fail("$name: ${e.message}") }
                val ignored = (expected["ignoredFields"] as? JsonArray)?.items?.map { (it as JsonString).value } ?: emptyList()
                assertEquals(ignored.toSet(), m.ignoredFields.toSet(), "$name: ignored fields")
                val dup = (expected["duplicateMembers"] as? JsonBoolean)?.value ?: false
                assertEquals(dup, m.duplicateMembers.isNotEmpty(), "$name: duplicates")
                expected.getObject("values")?.let { values ->
                    for (p in values.keys) assertEquals(values[p], m.json.at(p), "$name: $p")
                }
                // Through an archive, too.
                val a = MdzArchive.open(TestZip.build(listOf("index.md" to "# x".toByteArray(), "manifest.json" to text.toByteArray())))
                a.manifest()
                if (ignored.isNotEmpty()) assertTrue(a.validate().hasCode("WARN_FIELD_IGNORED"), name)
                if (dup) assertTrue(a.validate().hasCode("WARN_DUPLICATE_MEMBER"), name)
            } else {
                val code = expected.getString("errorCode")
                val e = catching<MdzException> { MdzManifest.parse(text) } ?: fail("$name: accepted")
                assertEquals(code, e.code.code, name)
                val a = MdzArchive.open(TestZip.build(listOf("index.md" to "# x".toByteArray(), "manifest.json" to text.toByteArray())))
                assertEquals(code, catching<MdzException> { a.manifest() }?.code?.code, "$name via archive")
                assertTrue(a.validate().hasCode(code!!), "$name: validate reports $code")
            }
        }
    }

    @Test
    fun t013EntryNames() = Results.run("T-013", "entry-set-cases.json and path-validation-cases.json, by checking and by opening") {
        val doc = Fixtures.json("entry-set-cases.json") as JsonObject
        for (c in (doc["cases"] as JsonArray).items.map { it as JsonObject }) {
            val name = c.getString("name")
            val entries = (c["entries"] as JsonArray).items.map { (it as JsonString).value }
            val bytes = TestZip.ofFiles(entries)
            if ((c["valid"] as JsonBoolean).value) {
                val a = MdzArchive.open(bytes)
                assertEquals(entries.size, a.entries.size, name)
            } else {
                expectCode(MdzErrorCode.fromCode(c.getString("errorCode")!!)!!) { MdzArchive.open(bytes) }
                assertTrue(MdzArchive.validate(bytes).hasCode(c.getString("errorCode")!!))
            }
        }
        for (c in (Fixtures.json("path-validation-cases.json") as JsonArray).items.map { it as JsonObject }) {
            val path = c.getString("path")!!
            val name = c.getString("name")
            val valid = (c["valid"] as JsonBoolean).value
            assertEquals(c.getString("errorCode"), MdzPaths.check(path)?.code, name)
            val bytes = TestZip.ofFiles(listOf("index.md", path).distinct())
            if (valid) {
                assertTrue(path in MdzArchive.open(bytes), name)
            } else {
                val e = expectCode(MdzErrorCode.PATH_INVALID) { MdzArchive.open(bytes) }
                assertEquals(c.getString("errorCode"), e.detail?.code, name)
            }
        }
    }

    @Test
    fun t014UnsupportedCompression() = Results.run("T-014", "rejected with ERR_ZIP_INVALID") {
        expectCode(MdzErrorCode.ZIP_INVALID) { Fixtures.archive("unsupported-compression.mdz") }
    }

    @Test
    fun t015ProjectMode() = Results.run("T-015", "project-capable: mode() is PROJECT, documents read separately") {
        val a = Fixtures.archive("project-mode.mdz")
        assertEquals(MdzMode.PROJECT, a.mode())
        assertEquals("index.md", a.entryPoint())
        val docs = a.documents()
        assertEquals(setOf("index.md", "chapters/one.md", "chapters/two.md"), docs.toSet())
        val texts = docs.map { a.readText(it) }
        assertEquals(texts.size, texts.toSet().size, "each document is its own content")
        for ((d, t) in docs.zip(texts)) {
            for (other in texts) if (other !== t) assertFalse(t.contains(other), "$d is not joined with another document")
        }
    }

    @Test
    fun t016HostileContent() = Results.notApplicable("T-016", "the library does not render Markdown")

    @Test
    fun t017Symlinks() = Results.run("T-017", "never followed; listed in specialEntries and reported by validate()") {
        val a = Fixtures.archive("symlink-entries.mdz")
        assertEquals("index.md", a.entryPoint())
        assertEquals(setOf("shortcut.md", "linked", "escape.md"), a.specialEntries.map { it.path }.toSet())
        assertTrue(a.specialEntries.all { it.isSymbolicLink })
        assertFalse("shortcut.md" in a)
        expectCode(MdzErrorCode.SPECIAL_ENTRY) { a.readBytes("shortcut.md") }
        expectCode(MdzErrorCode.SPECIAL_ENTRY) { a.readBytes("escape.md") }
        val cases = (Fixtures.json("archive-cases.json") as JsonObject)["cases"] as JsonArray
        val c = cases.items.map { it as JsonObject }.first { it.getString("id") == "symlink-entries" }
        for (l in (c["links"] as JsonArray).items.map { it as JsonObject }) {
            val r = a.resolveLink(l.getString("href")!!, "index.md")
            when (l.getString("expected")) {
                "resolved" -> assertEquals(l.getString("target"), (r as MdzLink.Internal).path)
                else -> assertTrue(r is MdzLink.Unresolved, "${l.getString("href")}: $r")
            }
        }
        val reported = a.validate().findings.filter { it.code == "WARN_SYMLINK_ENTRY" }.map { it.path }.toSet()
        assertEquals(setOf("shortcut.md", "linked", "escape.md"), reported)
    }

    @Test
    fun t018Limits() = Results.run("T-018") {
        val doc = Fixtures.json("resource-limit-cases.json") as JsonObject
        val ref = doc.getObject("referenceLimits")!!
        val d = Limits.DEFAULT
        fun num(k: String) = (ref[k] as JsonNumber).toBigDecimal()!!.toLong()
        assertTrue(d.maxEntryBytes <= num("maxEntryUncompressedBytes"))
        assertTrue(d.maxTotalBytes <= num("maxTotalUncompressedBytes"))
        assertTrue(d.maxCompressionRatio <= num("maxCompressionRatio"))
        assertTrue(d.ratioAppliesAboveBytes <= num("compressionRatioAppliesAboveBytes"))
        assertTrue(d.maxEntries <= num("maxEntries"))

        val outcomes = ArrayList<String>()
        for (c in (doc["cases"] as JsonArray).items.map { it as JsonObject }) {
            val id = c.getString("id")!!
            val bytes = Fixtures.bytes(c.getString("archive")!!)
            val allowed = when (id) {
                "limit-high-ratio" -> setOf(MdzErrorCode.LIMIT_EXCEEDED)
                else -> setOf(MdzErrorCode.LIMIT_EXCEEDED, MdzErrorCode.ZIP_INVALID)
            }
            val a = try {
                MdzArchive.open(bytes)
            } catch (e: MdzException) {
                assertTrue(e.code in allowed, "$id: ${e.code}")
                outcomes.add("$id: open fails ${e.code}")
                continue
            }
            assertTrue(id != "limit-overlapping-entries", "$id must be rejected at open")
            a.readText("index.md")
            val e = catching<MdzException> { a.readBytes(c.getString("entry")!!) } ?: fail("$id: entry was returned")
            assertTrue(e.code in allowed, "$id: ${e.code}")
            outcomes.add("$id: read fails ${e.code}")
        }
        // maxEntries, with a configured lower limit.
        val six = TestZip.ofFiles(listOf("index.md", "a.md", "b.md", "c.md", "d.md", "e.md"))
        expectCode(MdzErrorCode.LIMIT_EXCEEDED) { MdzArchive.open(six, Limits(maxEntries = 5)) }
        Results.record("T-018", "passed", outcomes.joinToString("; ") + "; entry count checked with a limit of 5")
    }
}
