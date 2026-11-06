package org.mdzip.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** This library's name and version, as written to `producer.core` in manifests it creates. */
public object MdzLibrary {
    public const val NAME: String = "mdzip-core-kotlin"
    public const val VERSION: String = "0.1.0"
    public const val URL: String = "https://github.com/mdzip-project/mdzip-core-kotlin"

    /** The specification version written to `spec.version`. */
    public const val SPEC_VERSION: String = "1.2.1"
}

/** What [MdzEditor] does with symbolic links and other special entries already in an archive (Section 14.1). */
public enum class SymlinkPolicy {
    /** Keep them unchanged. The saved archive is still non-conforming; tell the user (see [MdzEditor.specialEntries]). */
    KEEP,
    /** Store each as a regular file whose content is the stored link target text. */
    STORE_AS_FILE,
    /** Remove them. Only with the user's agreement: they are then gone from the archive. */
    REMOVE,
}

internal object Output {
    /** No limits: for re-reading what this library just wrote from data it already holds. */
    private val UNLIMITED = Limits(Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)

    /** Serializes entries, then reopens the result so that nothing unreadable is ever returned. */
    fun serialize(entries: List<ZipOutEntry>): Pair<ByteArray, MdzArchive> {
        val out = ByteArrayOutputStream()
        ZipWriter.write(entries, out)
        val bytes = out.toByteArray()
        val archive = try {
            MdzArchive.open(bytes, UNLIMITED)
        } catch (e: MdzException) {
            throw MdzException(e.code, "The archive written failed its own check: ${e.message}", e.path, e.detail, e)
        }
        return bytes to archive
    }

    fun manifestEntry(m: JsonObject, time: DosTime): ZipOutEntry =
        ZipOutEntry.file(MdzArchive.MANIFEST, Text.utf8(m.toPrettyJson() + "\n"), time)

    fun write(bytes: ByteArray, out: OutputStream) {
        try {
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
            throw MdzException(MdzErrorCode.IO, "Can't write the archive: ${e.message}", cause = e)
        }
    }

    /**
     * Writes [bytes] to [file] through a temporary file in the same folder, so a failure leaves
     * any existing file as it was.
     */
    fun writeAtomically(bytes: ByteArray, file: File) {
        val target = file.absoluteFile
        val dir = target.parentFile ?: throw MdzException(MdzErrorCode.IO, "No folder for ${file.path}")
        var tmp: File? = null
        try {
            tmp = File.createTempFile(".${target.name}.", ".tmp", dir)
            FileOutputStream(tmp).use { fos ->
                BufferedOutputStream(fos).let { it.write(bytes); it.flush() }
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // Windows can't rename over an existing file: move the old one aside first.
                if (!target.exists()) throw IOException("can't move the temporary file into place")
                val backup = File(dir, ".${target.name}.${System.nanoTime()}.bak")
                if (!target.renameTo(backup)) throw IOException("can't replace the existing file")
                if (!tmp.renameTo(target)) {
                    backup.renameTo(target)
                    throw IOException("can't move the temporary file into place")
                }
                backup.delete()
            }
        } catch (e: IOException) {
            throw MdzException(MdzErrorCode.IO, "Can't write ${file.path}: ${e.message}", cause = e)
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete()
        }
    }

    /** Adds `spec`, `producer.core` and `mode` when they are missing. */
    fun fillProducerFields(m: JsonObject) {
        val spec = m.getOrCreateObject("spec")
        if (!spec.containsKey("name")) spec.put("name", "mdzip-spec")
        if (!spec.containsKey("version")) spec.put("version", MdzLibrary.SPEC_VERSION)
        val producer = m.getOrCreateObject("producer")
        if (!producer.containsKey("core")) {
            producer.put("core", JsonObject()
                .put("name", MdzLibrary.NAME).put("version", MdzLibrary.VERSION).put("url", MdzLibrary.URL))
        }
        if (!m.containsKey("mode")) m.put("mode", MdzMode.DOCUMENT.value)
    }

    fun person(name: String, email: String?, url: String?): JsonObject =
        JsonObject().put("name", name).put("email", email).put("url", url)

    fun tool(name: String, version: String?, url: String?): JsonObject =
        JsonObject().put("name", name).put("version", version).put("url", url)

    /** Checks a path a caller wants to write, returning its NFC form. */
    fun checkNewPath(path: String, directory: Boolean = false): String {
        val p = if (directory) path.removeSuffix("/") else path
        val problem = MdzPaths.check(p)
        if (problem != null) fail(MdzErrorCode.PATH_INVALID, "\"$path\" breaks the path rules ($problem)", path, problem)
        if (p == MdzArchive.MANIFEST) {
            fail(MdzErrorCode.INVALID_ARGUMENT, "Change manifest.json through the manifest property, not as a file", path)
        }
        return MdzPaths.nfc(p)
    }

    /** Throws when [m] has a critical error, or a descriptive field that is invalid and wasn't before. */
    fun checkManifest(m: JsonObject, previouslyIgnored: Collection<String>) {
        val a = MdzManifest.analyze(m, emptyList())
        a.critical.firstOrNull()?.let { throw it }
        val bad = a.ignored.filter { it !in previouslyIgnored }
        if (bad.isNotEmpty()) {
            fail(MdzErrorCode.MANIFEST_INVALID, "Manifest field ${bad.joinToString(", ")} has a wrong type or value", bad[0])
        }
    }
}
