package org.mdzip.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The specification's producer checks (fixtures/README.md), run through the public API. */
class ProducerFixtureTests {
    /** P-007's checks, applied to every archive the producer tests write. */
    private fun assertConforming(bytes: ByteArray, allowSymlinks: Boolean = false) {
        val raw = RawZip(bytes)
        assertFalse(raw.hasZip64Records || raw.hasZip64Extra(), "no ZIP64 records")
        for (r in raw.records) {
            assertTrue(r.method == 0 || r.method == 8, "${r.name}: method ${r.method}")
            if (!Text.isAscii(r.name)) assertTrue((r.flags and 0x800) != 0, "${r.name}: UTF-8 flag")
            assertEquals(null, MdzPaths.checkEntryName(r.name), r.name)
            val type = ((r.external ushr 16) and 0xF000L).toInt()
            if (!allowSymlinks) assertTrue(type == 0x8000 || type == 0x4000, "${r.name}: regular file or directory")
        }
        val a = MdzArchive.open(bytes)
        if (a.hasManifest) {
            val parsed = JsonParser(Text.decodeUtf8Strict(a.readBytes("manifest.json"))).parseDocument()
            assertTrue(parsed.duplicates.isEmpty(), "no duplicate manifest members")
        }
    }

    private fun mdzValidateSample(name: String, bytes: ByteArray) = saveSample(name, bytes)

    @Test
    fun p001NewArchive() = Results.run("P-001") {
        val bytes = MdzBuilder()
            .setApplication("mdzip-core-kotlin tests", "0.1.0", "https://example.com/tests")
            .setAuthor("Ada Lovelace", "ada@example.com")
            .addText("index.md", "# My Guide\r\n\r\nSee [notes](notes/café.md).\r\n")
            .addText("notes/café.md", "# Café\n")
            .addBytes("images/logo.png", byteArrayOf(1, 2, 3))
            .setCover("images/logo.png")
            .toByteArray()
        assertConforming(bytes)
        mdzValidateSample("created.mdz", bytes)
        val a = MdzArchive.open(bytes)
        val m = assertNotNull(a.manifest())
        assertEquals(MdzLibrary.SPEC_VERSION, m.specVersion)
        assertEquals("index.md", m.entryPoint)
        assertTrue(MdzPaths.isMarkdown(m.entryPoint!!) && m.entryPoint!! in a)
        assertEquals("My Guide", m.title)
        assertTrue(m.json.containsKey("mode"))
        assertEquals(MdzLibrary.NAME, m.producerCore?.name)
        assertNotNull(m.created)
        assertEquals("# My Guide\n\nSee [notes](notes/café.md).\n", a.readText("index.md"), "Markdown gets LF")
        assertTrue("notes/café.md" in a.files, "paths are stored in NFC")
        assertTrue(a.validate().findings.none { it.severity != MdzSeverity.INFO }, a.validate().toString())

        val project = MdzBuilder().setMode(MdzMode.PROJECT).addText("index.md", "# Home\n")
            .addText("guide/one.md", "# One\n").addText("guide/two.md", "# Two\n").toByteArray()
        assertConforming(project)
        mdzValidateSample("project.mdz", project)
        assertEquals(MdzMode.PROJECT, MdzArchive.open(project).mode())
    }

    @Test
    fun p002AddSupportFileToContentArchive() = Results.run("P-002") {
        val source = MdzArchive.open(TestZip.ofFiles(listOf("notes.md")))
        val bytes = source.edit().setText("AGENTS.md", "# Agents\n").toByteArray()
        assertConforming(bytes)
        mdzValidateSample("added-agents.mdz", bytes)
        val a = MdzArchive.open(bytes)
        assertEquals("notes.md", a.entryPoint())
        assertEquals("notes.md", a.manifest()!!.entryPoint)
    }

    @Test
    fun p003AddSecondSupportFile() = Results.run("P-003", "manifest added even with addManifestIfMissing = false") {
        for (addIfMissing in listOf(true, false)) {
            val editor = MdzArchive.open(TestZip.ofFiles(listOf("README.md"))).edit()
            editor.addManifestIfMissing = addIfMissing
            val a = MdzArchive.open(editor.setText("AGENTS.md", "# Agents\n").toByteArray())
            assertEquals("README.md", a.manifest()!!.entryPoint)
            assertEquals("README.md", a.entryPoint())
        }
    }

    @Test
    fun p004p005RoundTrip() {
        val exp = Fixtures.json("round-trip-expectations.json") as JsonObject
        val values = (exp["preservedManifestValues"] as JsonArray).items.map { it as JsonObject }
        val entries = (exp["preservedEntries"] as JsonArray).items.map { it as JsonObject }
        val flows = (exp["flows"] as JsonArray).items.map { it as JsonObject }
        var p4 = true
        try {
            Results.run("P-005", "five flows, each saved twice") {
                for (flow in flows) {
                    val name = flow.getString("name")!!
                    val changes = (flow["changes"] as JsonArray).items.map { (it as JsonString).value }
                    val removes = (flow["removes"] as? JsonArray)?.items?.map { (it as JsonString).value } ?: emptyList()
                    var bytes = Fixtures.bytes("round-trip.mdz")
                    repeat(2) { round ->
                        val e = MdzArchive.open(bytes).edit()
                        when (name) {
                            "open and save without an edit" -> {}
                            "change a recognized manifest field" -> e.setTitle("Edited title")
                            "edit document content" -> {
                                val a = MdzArchive.open(bytes)
                                e.setText("index.md", a.readText("index.md") + "Appended line ${round + 1}.\n")
                            }
                            "add a resource" -> e.setBytes("images/added.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
                            "remove a recognized resource" -> {
                                val a = MdzArchive.open(bytes)
                                e.remove("docs/guide.md")
                                e.setText("index.md", a.readText("index.md").lines().filterNot { it.contains("docs/guide.md") }.joinToString("\n"))
                            }
                            else -> error("unknown flow $name")
                        }
                        bytes = e.toByteArray()
                        assertConforming(bytes)
                        val a = MdzArchive.open(bytes)
                        assertEquals("index.md", a.entryPoint(), "$name: entry point")
                        val m = a.manifest()!!.json
                        for (v in values) {
                            if (m.at(v.getString("pointer")!!) != v["value"]) p4 = false
                            assertEquals(v["value"], m.at(v.getString("pointer")!!), "$name round ${round + 1}: ${v.getString("pointer")}")
                        }
                        for (pe in entries) {
                            val path = pe.getString("path")!!
                            if (path in changes) continue
                            if (path in removes) { assertFalse(path in a, "$name: $path removed"); continue }
                            val data = a.readBytes(path)
                            assertEquals((pe["bytes"] as JsonNumber).text.toInt(), data.size, "$name: $path size")
                            assertEquals(pe.getString("sha256"), Fixtures.sha256(data), "$name: $path SHA-256")
                        }
                        if (name == "change a recognized manifest field") assertEquals("Edited title", a.manifest()!!.title)
                        if (name == "add a resource") assertTrue("images/added.png" in a)
                    }
                    if (name == "edit document content") mdzValidateSample("round-trip-edited.mdz", bytes)
                }
            }
        } finally {
            Results.record("P-004", if (p4) "passed" else "FAILED", "every preservedManifestValues pointer after each of 10 saves")
        }
    }

    @Test
    fun p006CreateVersusModify() = Results.run("P-006") {
        // Creating always writes a manifest.
        assertTrue(MdzArchive.open(MdzBuilder().addText("index.md", "# A\n").toByteArray()).hasManifest)
        // Modifying a manifest-less archive: conforming with or without adding one.
        for (add in listOf(false, true)) {
            val e = Fixtures.archive("minimal.mdz").edit()
            e.addManifestIfMissing = add
            e.setText("index.md", "# Edited\n")
            val a = MdzArchive.open(e.toByteArray())
            assertEquals(add, a.hasManifest)
            assertEquals("index.md", a.entryPoint())
            if (add) mdzValidateSample("minimal-edited.mdz", e.toByteArray())
        }
    }

    @Test
    fun p007EntryNamesAndFlags() = Results.run("P-007", "checked on every archive the producer tests write") {
        val bytes = MdzBuilder().addText("index.md", "# Ünïcode\n").addText("文档/介绍.md", "# 介绍\n")
            .addDirectory("empty/").toByteArray()
        assertConforming(bytes)
        val raw = RawZip(bytes)
        assertTrue(raw.records.first { it.name.startsWith("文") }.flags and 0x800 != 0)
        assertTrue(raw.records.first { it.name == "index.md" }.flags and 0x800 == 0, "ASCII names need no flag")
        mdzValidateSample("unicode-names.mdz", bytes)
    }

    @Test
    fun p008ModifyArchiveWithSymlinks() = Results.run("P-008", "kept and reported by default; STORE_AS_FILE and REMOVE on request") {
        val source = Fixtures.archive("symlink-entries.mdz")
        val links = mapOf("shortcut.md" to "notes.md", "linked" to "docs", "escape.md" to "../../outside-the-archive.md")

        val keep = source.edit()
        assertEquals(links.keys, keep.specialEntries.toSet(), "reported to the caller")
        val kept = MdzArchive.open(keep.setText("notes.md", "# Notes, edited\n").toByteArray())
        assertEquals(links.keys, kept.specialEntries.map { it.path }.toSet(), "never dropped silently")
        assertEquals(3, RawZip(keep.toByteArray()).records.count { ((it.external ushr 16) and 0xF000L) == 0xA000L }, "no new links")

        val files = source.edit().apply { symlinkPolicy = SymlinkPolicy.STORE_AS_FILE }
        val asFiles = files.setText("notes.md", "# Notes, edited\n").toByteArray()
        assertConforming(asFiles)
        mdzValidateSample("symlinks-as-files.mdz", asFiles)
        val f = MdzArchive.open(asFiles)
        for ((name, target) in links) assertEquals(target, f.readText(name))
        assertTrue(f.specialEntries.isEmpty())

        val removed = source.edit().apply { symlinkPolicy = SymlinkPolicy.REMOVE }.setText("notes.md", "# x\n").toByteArray()
        assertConforming(removed)
        val r = MdzArchive.open(removed)
        for (name in links.keys) assertNull(r.entry(name))
        assertEquals("index.md", r.entryPoint())
    }
}
