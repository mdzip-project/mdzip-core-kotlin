package org.mdzip.core

import java.io.File
import java.io.OutputStream

/**
 * Creates a new archive (R5, a *creation* in Section 14.1).
 *
 * Add files, set metadata, then call [toByteArray], [writeTo] with a stream, or [writeTo] with a
 * file. The archive always gets a `manifest.json` with `spec`, `producer.core`, `title`, `mode`,
 * `entryPoint` and `created`. Paths are stored in NFC; Markdown added as text gets LF line endings.
 *
 * ```kotlin
 * val bytes = MdzBuilder()
 *     .setTitle("My Guide")
 *     .addText("index.md", "# My Guide\n")
 *     .addBytes("images/logo.png", png)
 *     .toByteArray()
 * ```
 */
public class MdzBuilder {
    private class Item(val path: String, val bytes: ByteArray?) // bytes == null: directory entry

    private val items = LinkedHashMap<String, Item>()
    private var entryPoint: String? = null

    /**
     * Extra manifest members, merged into the manifest this builder writes. Use it for any field,
     * including ones this library doesn't know. The setters below write into it too.
     */
    public val manifest: JsonObject = JsonObject()

    /**
     * Adds a text file, stored as UTF-8. Markdown files (`.md`, `.markdown`) get LF line
     * endings; other files are stored as given. Adding a path again replaces it.
     * @throws MdzException [MdzErrorCode.PATH_INVALID] for a bad path.
     */
    @Throws(MdzException::class)
    public fun addText(path: String, text: String): MdzBuilder {
        val p = Output.checkNewPath(path)
        items[p] = Item(p, Text.utf8(if (MdzPaths.isMarkdown(p)) Text.toLf(text) else text))
        return this
    }

    /** Adds a file with the given bytes, unchanged. Adding a path again replaces it. */
    @Throws(MdzException::class)
    public fun addBytes(path: String, bytes: ByteArray): MdzBuilder {
        val p = Output.checkNewPath(path)
        items[p] = Item(p, bytes.copyOf())
        return this
    }

    /** Adds an (optional) directory entry, for example for an empty folder. */
    @Throws(MdzException::class)
    public fun addDirectory(path: String): MdzBuilder {
        val p = Output.checkNewPath(path, directory = true) + "/"
        items[p] = Item(p, null)
        return this
    }

    /** Sets the entry point. Without it, `index.md` or the only root Markdown file is used. */
    @Throws(MdzException::class)
    public fun setEntryPoint(path: String): MdzBuilder {
        entryPoint = MdzPaths.normalizeEntryPoint(path)
            ?: fail(MdzErrorCode.ENTRYPOINT_INVALID, "\"$path\" is not a relative path to a .md or .markdown file", path)
        return this
    }

    /** Sets `title`. Without it, the entry point's first `# ` heading or file name is used. */
    public fun setTitle(title: String?): MdzBuilder = apply { manifest.put("title", title) }

    /** Sets `mode`. The default is [MdzMode.DOCUMENT]. */
    public fun setMode(mode: MdzMode): MdzBuilder = apply { manifest.put("mode", mode.value) }

    public fun setLanguage(language: String?): MdzBuilder = apply { manifest.put("language", language) }
    public fun setDescription(description: String?): MdzBuilder = apply { manifest.put("description", description) }
    public fun setLicense(license: String?): MdzBuilder = apply { manifest.put("license", license) }

    /** Sets `cover`, the path of a cover image in the archive. */
    public fun setCover(path: String?): MdzBuilder = apply { manifest.put("cover", path) }

    /** Sets `author`. */
    @JvmOverloads
    public fun setAuthor(name: String, email: String? = null, url: String? = null): MdzBuilder =
        apply { manifest.put("author", Output.person(name, email, url)) }

    /** Sets `producer.application`: the app that creates the archive. */
    @JvmOverloads
    public fun setApplication(name: String, version: String? = null, url: String? = null): MdzBuilder =
        apply { manifest.getOrCreateObject("producer").put("application", Output.tool(name, version, url)) }

    /**
     * Builds the archive.
     * @throws MdzException [MdzErrorCode.ENTRYPOINT_UNRESOLVED] when no entry point is set and
     *   none can be chosen; [MdzErrorCode.ENTRYPOINT_MISSING] when it names no file;
     *   [MdzErrorCode.PATH_CONFLICT] for a file that is also a directory;
     *   [MdzErrorCode.MANIFEST_INVALID] for a manifest field with a wrong type or value.
     */
    @Throws(MdzException::class)
    public fun toByteArray(): ByteArray {
        val files = items.values.filter { it.bytes != null }.map { it.path }
        if (files.isEmpty()) fail(MdzErrorCode.INVALID_ARGUMENT, "Add at least one Markdown file")
        MdzArchive.checkNameSet(listOf(MdzArchive.MANIFEST) + items.keys)

        val m = manifest.deepCopy()
        m.remove("entryPoint")
        val ep = when (val requested = entryPoint ?: manifest.getString("entryPoint")?.let { MdzPaths.normalizeEntryPoint(it) }) {
            null -> EntryPoints.resolve(files, null)
            else -> files.firstOrNull { MdzPaths.matches(it, requested) }
                ?: fail(MdzErrorCode.ENTRYPOINT_MISSING, "Entry point \"$requested\" is not one of the files added", requested)
        }
        Output.fillProducerFields(m)
        if (!m.containsKey("title")) {
            val text = items[ep]?.bytes?.let { if (Text.isValidUtf8(it)) Text.decodeUtf8Strict(it) else null }
            m.put("title", Text.deriveTitle(ep, text))
        }
        m.put("entryPoint", ep)
        if (!m.containsKey("created")) m.put("created", JsonObject().put("when", Text.nowRfc3339()))
        Output.checkManifest(m, emptyList())
        m.getString("cover")?.let { c ->
            val cover = MdzPaths.normalizeReference(c)!!
            if (files.none { MdzPaths.matches(it, cover) }) {
                fail(MdzErrorCode.ENTRY_NOT_FOUND, "cover \"$cover\" is not one of the files added", cover)
            }
        }

        val time = DosTime.now()
        val entries = ArrayList<ZipOutEntry>()
        entries.add(Output.manifestEntry(reorder(m), time))
        for (item in items.values) {
            entries.add(if (item.bytes == null) ZipOutEntry.directory(item.path, time) else ZipOutEntry.file(item.path, item.bytes, time))
        }
        return Output.serialize(entries).first
    }

    /** Writes the archive to a stream (for example from `ContentResolver.openOutputStream`). The stream is not closed. */
    @Throws(MdzException::class)
    public fun writeTo(out: OutputStream) {
        Output.write(toByteArray(), out)
    }

    /** Writes the archive to a file, replacing it only once the new archive is complete. */
    @Throws(MdzException::class)
    public fun writeTo(file: File) {
        Output.writeAtomically(toByteArray(), file)
    }

    /** Puts the well-known fields first so the manifest reads naturally; other members follow in order. */
    private fun reorder(m: JsonObject): JsonObject {
        val out = JsonObject()
        for (k in ORDER) m[k]?.let { out.put(k, it) }
        for (k in m.keys) if (!out.containsKey(k)) out.put(k, m[k]!!)
        return out
    }

    private companion object {
        val ORDER = listOf("spec", "producer", "author", "title", "mode", "entryPoint", "language", "description",
            "version", "created", "modified", "license", "keywords", "cover")
    }
}
