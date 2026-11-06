package org.mdzip.core

import java.io.File
import java.io.OutputStream

/**
 * Modifies an existing archive (R6, a *modification* in Section 14.1). Get one from
 * [MdzArchive.edit], make changes, then save with [toByteArray] or [writeTo].
 *
 * What is kept:
 * - Every entry you don't change keeps its stored path and its bytes, including files this
 *   library doesn't recognize, nested support files and directory entries.
 * - Every manifest member, including unknown ones at any depth ([manifest] is the full JSON).
 * - The entry point: it keeps resolving to the same file unless you call [setEntryPoint]. When an
 *   edit would change what discovery picks (for example adding a second support file), the
 *   manifest's `entryPoint` is set to keep it. Removing the entry point file is rejected.
 *
 * A manifest is added to an archive that has none (turn off with [addManifestIfMissing]), and
 * `modified.when` is set when anything changed ([updateModified]). Saving with no changes
 * rewrites nothing in the entries. A failed save throws and returns nothing, and [writeTo] with
 * a file leaves the existing file untouched.
 */
public class MdzEditor internal constructor(private val source: MdzArchive) {
    private sealed class Item {
        abstract val path: String
        class Kept(val entry: MdzEntry) : Item() { override val path: String get() = entry.path }
        class Content(override val path: String, val bytes: ByteArray) : Item()
        class Dir(override val path: String) : Item()
    }

    private val items = ArrayList<Item>()
    private val hadManifest = source.hasManifest
    private val originalManifest: JsonObject?
    private val originalIgnored: List<String>
    private val entryPointBefore: String?
    private var entryPointSet = false
    private var contentChanged = false

    /**
     * The manifest as JSON, to read or change any member: `editor.manifest.put("title", "New")`.
     * Empty when the archive has none. Changes are checked when saving.
     */
    public val manifest: JsonObject

    /** Add a `manifest.json` when the archive has none (Section 14.1 recommends it). Default true. */
    public var addManifestIfMissing: Boolean = true

    /** Set `modified.when` to now when anything changed. Default true. */
    public var updateModified: Boolean = true

    /** What to do with symbolic links and other special entries already in the archive. Default [SymlinkPolicy.KEEP]. */
    public var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.KEEP

    /**
     * Paths of symbolic links and other special entries in the archive. Show them to the user:
     * with [SymlinkPolicy.KEEP] the saved archive still contains them and is not conforming.
     */
    public val specialEntries: List<String> = source.specialEntries.map { it.path }

    init {
        if (hadManifest) {
            val a = MdzManifest.analyze(source.readBytes(MdzArchive.MANIFEST))
            // An invalid entryPoint can be repaired with setEntryPoint; other manifest errors can't be edited safely.
            a.critical.firstOrNull { it.code != MdzErrorCode.ENTRYPOINT_INVALID }?.let { throw it }
            originalManifest = a.json!!
            originalIgnored = a.ignored
            manifest = a.json.deepCopy()
        } else {
            originalManifest = null
            originalIgnored = emptyList()
            manifest = JsonObject()
        }
        entryPointBefore = try {
            EntryPoints.resolve(source.files, originalManifest)
        } catch (e: MdzException) {
            null
        }
        for (e in source.entries) if (e.path != MdzArchive.MANIFEST) items.add(Item.Kept(e))
    }

    private fun indexOf(nfcPath: String): Int = items.indexOfFirst { MdzPaths.nfc(it.path.removeSuffix("/")) == nfcPath }

    /** Paths of the regular files the saved archive will contain (not counting `manifest.json`). */
    public val files: List<String>
        get() = items.filter { (it is Item.Kept && it.entry.isFile) || it is Item.Content }.map { it.path }

    /** True when a regular file matches [path] (NFC, case-sensitive). */
    public operator fun contains(path: String): Boolean = files.any { MdzPaths.matches(it, path) }

    /**
     * Adds or replaces a text file, stored as UTF-8. Markdown files get LF line endings.
     * Replacing keeps the entry's stored name and position.
     */
    @Throws(MdzException::class)
    public fun setText(path: String, text: String): MdzEditor =
        setBytesInternal(path, Text.utf8(if (MdzPaths.isMarkdown(path)) Text.toLf(text) else text))

    /** Adds or replaces a file with the given bytes, unchanged. */
    @Throws(MdzException::class)
    public fun setBytes(path: String, bytes: ByteArray): MdzEditor = setBytesInternal(path, bytes.copyOf())

    private fun setBytesInternal(path: String, bytes: ByteArray): MdzEditor {
        val p = Output.checkNewPath(path)
        val i = indexOf(p)
        if (i >= 0 && items[i] is Item.Dir || (i >= 0 && (items[i] as? Item.Kept)?.entry?.isDirectory == true)) {
            fail(MdzErrorCode.PATH_CONFLICT, "\"$path\" is a directory", path)
        }
        if (i >= 0) items[i] = Item.Content(items[i].path.removeSuffix("/"), bytes) else items.add(Item.Content(p, bytes))
        contentChanged = true
        return this
    }

    /** Adds an (optional) directory entry. */
    @Throws(MdzException::class)
    public fun addDirectory(path: String): MdzEditor {
        val p = Output.checkNewPath(path, directory = true)
        if (indexOf(p) < 0) {
            items.add(Item.Dir("$p/"))
            contentChanged = true
        }
        return this
    }

    /**
     * Removes a file or directory entry (a directory path ends in `/`). Returns false when there
     * is no such entry. Removing the entry point fails when saving unless you set a new one.
     */
    public fun remove(path: String): Boolean {
        if (path == MdzArchive.MANIFEST) {
            throw MdzException(MdzErrorCode.INVALID_ARGUMENT, "manifest.json can't be removed", path)
        }
        val i = indexOf(MdzPaths.nfc(path.removeSuffix("/")))
        if (i < 0) return false
        items.removeAt(i)
        contentChanged = true
        return true
    }

    /** Changes the entry point. The file must exist when saving. */
    @Throws(MdzException::class)
    public fun setEntryPoint(path: String): MdzEditor {
        val p = MdzPaths.normalizeEntryPoint(path)
            ?: fail(MdzErrorCode.ENTRYPOINT_INVALID, "\"$path\" is not a relative path to a .md or .markdown file", path)
        manifest.put("entryPoint", p)
        entryPointSet = true
        return this
    }

    /** Sets `title`. */
    public fun setTitle(title: String?): MdzEditor = apply { manifest.put("title", title) }

    /** Sets `mode`. */
    public fun setMode(mode: MdzMode): MdzEditor = apply { manifest.put("mode", mode.value) }

    /** Sets `producer.application`: the app saving the archive. */
    @JvmOverloads
    public fun setApplication(name: String, version: String? = null, url: String? = null): MdzEditor =
        apply { manifest.getOrCreateObject("producer").put("application", Output.tool(name, version, url)) }

    /**
     * Builds the modified archive.
     * @throws MdzException [MdzErrorCode.ENTRYPOINT_MISSING] when the entry point file was removed
     *   or [setEntryPoint] names no file; [MdzErrorCode.PATH_CONFLICT]; [MdzErrorCode.MANIFEST_INVALID]
     *   for a manifest change with a wrong type or value; or a read error from an existing entry.
     */
    @Throws(MdzException::class)
    public fun toByteArray(): ByteArray {
        val kept = items.filter { it !is Item.Kept || !it.entry.isSpecial || symlinkPolicy != SymlinkPolicy.REMOVE }
        val finalFiles = kept.filter {
            (it is Item.Kept && (it.entry.isFile || symlinkPolicy == SymlinkPolicy.STORE_AS_FILE && it.entry.isSpecial)) ||
                it is Item.Content
        }.map { it.path }
        MdzArchive.checkNameSet(listOf(MdzArchive.MANIFEST) + kept.map { it.path })

        val m = manifest.deepCopy()
        val explicit = entryPointSet || m["entryPoint"] != originalManifest?.get("entryPoint")
        if (explicit) {
            EntryPoints.resolve(finalFiles, m) // the new entry point must exist
        } else if (entryPointBefore != null) {
            if (finalFiles.none { it == entryPointBefore }) {
                fail(MdzErrorCode.ENTRYPOINT_MISSING,
                    "The entry point \"$entryPointBefore\" was removed; call setEntryPoint to choose another", entryPointBefore)
            }
            val after = try {
                EntryPoints.resolve(finalFiles, if (!hadManifest && m.isEmpty()) null else m)
            } catch (e: MdzException) {
                null
            }
            if (after != entryPointBefore || (!hadManifest && addManifestIfMissing)) m.put("entryPoint", entryPointBefore)
        }

        val writeManifest = hadManifest || !m.isEmpty()
        val manifestChanged = if (hadManifest) m != originalManifest else writeManifest
        val changed = contentChanged || manifestChanged
        if (writeManifest && !hadManifest) {
            Output.fillProducerFields(m)
            if (!m.containsKey("title")) {
                val ep = m.getString("entryPoint")
                val text = ep?.let { p -> contentOf(p) }?.let { if (Text.isValidUtf8(it)) Text.decodeUtf8Strict(it) else null }
                if (ep != null) m.put("title", Text.deriveTitle(ep, text))
            }
        }
        if (writeManifest && changed && updateModified) {
            val modified = m["modified"] as? JsonObject ?: JsonObject().also { m.put("modified", it) }
            modified.put("when", Text.nowRfc3339())
        }
        if (writeManifest) {
            Output.checkManifest(m, originalIgnored)
            checkCover(m, finalFiles)
        }

        val time = DosTime.now()
        val entries = ArrayList<ZipOutEntry>()
        if (writeManifest) {
            entries.add(
                if (hadManifest && !changed) keep(source.entry(MdzArchive.MANIFEST)!!, source.readBytes(MdzArchive.MANIFEST))
                else Output.manifestEntry(m, time)
            )
        }
        for (item in kept) {
            entries.add(
                when (item) {
                    is Item.Content -> ZipOutEntry.file(item.path, item.bytes, time)
                    is Item.Dir -> ZipOutEntry.directory(item.path, time)
                    is Item.Kept -> when {
                        item.entry.isSpecial && symlinkPolicy == SymlinkPolicy.STORE_AS_FILE ->
                            ZipOutEntry.file(item.path, ZipReader.readData(source.rawBytes, source.record(item.entry)), time)
                        item.entry.isSpecial -> keepSpecial(item.entry)
                        item.entry.isDirectory -> keepDirectory(item.entry)
                        else -> keep(item.entry, source.readBytes(item.path))
                    }
                }
            )
        }
        val (bytes, written) = Output.serialize(entries)
        // The entry point must still be the same file (Section 14.1), unless the caller changed it.
        if (!explicit && entryPointBefore != null) {
            val after = written.entryPoint()
            if (after != entryPointBefore) {
                fail(MdzErrorCode.ENTRYPOINT_MISSING, "The entry point would change from \"$entryPointBefore\" to \"$after\"")
            }
        }
        return bytes
    }

    /** Writes the modified archive to a stream. The stream is not closed. */
    @Throws(MdzException::class)
    public fun writeTo(out: OutputStream) {
        Output.write(toByteArray(), out)
    }

    /**
     * Writes the modified archive to a file (it may be the one the archive came from). The file
     * is replaced only once the new archive is complete; on any failure it is left as it was.
     */
    @Throws(MdzException::class)
    public fun writeTo(file: File) {
        Output.writeAtomically(toByteArray(), file)
    }

    private fun contentOf(path: String): ByteArray? {
        val i = indexOf(MdzPaths.nfc(path))
        if (i < 0) return null
        return when (val item = items[i]) {
            is Item.Content -> item.bytes
            is Item.Kept -> if (item.entry.isFile) source.readBytes(item.path) else null
            is Item.Dir -> null
        }
    }

    private fun checkCover(m: JsonObject, finalFiles: List<String>) {
        val cover = m.getString("cover")?.let { MdzPaths.normalizeReference(it) } ?: return
        val existedBefore = originalManifest?.getString("cover")?.let { MdzPaths.normalizeReference(it) } == cover &&
            source.entry(cover)?.isFile == true
        val newlySet = originalManifest?.get("cover") != m["cover"]
        if ((existedBefore || newlySet) && finalFiles.none { MdzPaths.matches(it, cover) }) {
            fail(MdzErrorCode.ENTRY_NOT_FOUND, "cover \"$cover\" would name a missing file; change or remove cover", cover)
        }
    }

    /** Copies an entry's compressed bytes unchanged, after [verified] proved they decompress correctly. */
    private fun keep(e: MdzEntry, @Suppress("UNUSED_PARAMETER") verified: ByteArray): ZipOutEntry {
        val r = source.record(e)
        return ZipOutEntry(e.path, r.method, r.crc, r.size, source.rawData(e), r.dosTime, r.dosDate,
            ZipOutEntry.ATTR_FILE, ZipOutEntry.MADE_BY_UNIX)
    }

    private fun keepDirectory(e: MdzEntry): ZipOutEntry {
        val r = source.record(e)
        return ZipOutEntry(e.path, 0, 0, 0, ByteArray(0), r.dosTime, r.dosDate, ZipOutEntry.ATTR_DIR, ZipOutEntry.MADE_BY_UNIX)
    }

    private fun keepSpecial(e: MdzEntry): ZipOutEntry {
        val r = source.record(e)
        ZipReader.readData(source.rawBytes, r) // verify before copying
        return ZipOutEntry(e.path, r.method, r.crc, r.size, source.rawData(e), r.dosTime, r.dosDate, r.externalAttrs, r.versionMadeBy)
    }
}
