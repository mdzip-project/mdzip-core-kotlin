package org.mdzip.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** One entry as stored in the archive. */
public class MdzEntry internal constructor(internal val record: ZipRecord) {
    /** The stored path. Directory entries end in `/`. */
    public val path: String get() = record.name

    /** True for a directory entry (its name ends in `/`). Directories carry no content. */
    public val isDirectory: Boolean get() = record.isDirectory && !record.isSpecial

    /** True for a symbolic link entry. It is never read, followed or resolved to. */
    public val isSymbolicLink: Boolean get() = record.isSymlink

    /** True for a symbolic link or any other entry that is neither a regular file nor a directory. */
    public val isSpecial: Boolean get() = record.isSpecial

    /** True for a regular file. */
    public val isFile: Boolean get() = !isDirectory && !isSpecial

    /** The uncompressed size the archive declares. Reads never return more than this. */
    public val size: Long get() = record.size

    /** The compressed size the archive declares. */
    public val compressedSize: Long get() = record.compressedSize

    /** The compression method: 0 (stored) or 8 (deflate). */
    public val method: Int get() = record.method

    override fun toString(): String = path
}

/**
 * An MDZip archive opened for reading. It holds the archive's bytes in memory; nothing is
 * extracted. Instances are immutable and safe to share between threads.
 *
 * Opening checks the ZIP structure, the resource limits on declared sizes, and every entry name
 * (Sections 4, 5.4 and 13.1): bad names, NFC duplicates and file/directory conflicts are rejected.
 * The manifest is checked by [manifest], [entryPoint] and [mode], so that an archive with a bad
 * manifest can still be inspected; [validate] reports everything at once.
 */
public class MdzArchive private constructor(
    private val bytes: ByteArray,
    private val zip: ZipDirectory,
) {
    /** Every entry, in central directory order. */
    public val entries: List<MdzEntry> = zip.records.map { MdzEntry(it) }

    private val byNfc: Map<String, MdzEntry> = HashMap<String, MdzEntry>().also { m ->
        for (e in entries) m[MdzPaths.nfc(e.path.removeSuffix("/"))] = e
    }

    /** Paths of the regular files. */
    public val files: List<String> = entries.filter { it.isFile }.map { it.path }

    /** Paths of the directory entries (each ends in `/`). Directory entries are optional in ZIP files. */
    public val directories: List<String> = entries.filter { it.isDirectory }.map { it.path }

    /**
     * Symbolic links and other special entries. They are never read or followed, never count as
     * files for the entry point or links, and should be reported to the user (Section 13.1).
     */
    public val specialEntries: List<MdzEntry> = entries.filter { it.isSpecial }

    /** True when the archive has a `manifest.json` file at its root. */
    public val hasManifest: Boolean get() = contains(MANIFEST)

    /** The entry whose path matches [path] (NFC, case-sensitive), or `null`. Directories match with or without `/`. */
    public fun entry(path: String): MdzEntry? = byNfc[MdzPaths.nfc(path.removeSuffix("/"))]

    /** True when a regular file matches [path] exactly (NFC, case-sensitive). */
    public operator fun contains(path: String): Boolean = entry(path)?.isFile == true

    /**
     * A file whose path differs from [path] only in case, for a "did you mean" hint. It is
     * never used in place of [path] (Section 5.4.1).
     */
    public fun nearMatch(path: String): String? = if (contains(path)) null else EntryPoints.nearMatch(files, path)

    /**
     * The bytes of a file. Decompression stops at the size the archive declares.
     * @throws MdzException [MdzErrorCode.ENTRY_NOT_FOUND], [MdzErrorCode.SPECIAL_ENTRY] (symbolic
     *   link or other special entry), [MdzErrorCode.ZIP_INVALID] (damaged data) or
     *   [MdzErrorCode.LIMIT_EXCEEDED] (more data than declared).
     */
    @Throws(MdzException::class)
    public fun readBytes(path: String): ByteArray {
        val e = entry(path)
        if (e == null || e.isDirectory) {
            val hint = nearMatch(path)?.let { " (did you mean \"$it\"?)" } ?: ""
            fail(MdzErrorCode.ENTRY_NOT_FOUND, "No file \"$path\" in the archive$hint", path)
        }
        if (e.isSpecial) fail(MdzErrorCode.SPECIAL_ENTRY, "\"${e.path}\" is a symbolic link or special entry and is not read", e.path)
        return ZipReader.readData(bytes, e.record)
    }

    /**
     * A file decoded as UTF-8. A leading byte order mark is removed; line endings are kept.
     * @throws MdzException [MdzErrorCode.TEXT_DECODING] if the file is not valid UTF-8, or as [readBytes].
     */
    @Throws(MdzException::class)
    public fun readText(path: String): String {
        val text = Text.decodeUtf8Strict(readBytes(path), path)
        return if (text.startsWith("﻿")) text.substring(1) else text
    }

    private val manifestResult: Any? by lazy {
        if (!hasManifest) return@lazy null
        try {
            MdzManifest.parse(readBytes(MANIFEST))
        } catch (e: MdzException) {
            e
        }
    }

    /**
     * The validated manifest, or `null` when the archive has none (which is valid).
     * @throws MdzException [MdzErrorCode.MANIFEST_INVALID], [MdzErrorCode.VERSION_UNSUPPORTED],
     *   [MdzErrorCode.MODE_UNSUPPORTED] or [MdzErrorCode.ENTRYPOINT_INVALID] (Section 6.4).
     */
    @Throws(MdzException::class)
    public fun manifest(): MdzManifest? {
        val r = manifestResult
        if (r is MdzException) throw MdzException(r.code, r.message!!.substringAfter(": "), r.path, r.detail, r)
        return r as MdzManifest?
    }

    /**
     * The entry point: the stored path of the primary Markdown file (Section 5.5). A declared
     * `entryPoint` that is invalid or names a missing file is an error, never a silent fallback.
     * @throws MdzException [MdzErrorCode.ENTRYPOINT_INVALID], [MdzErrorCode.ENTRYPOINT_MISSING],
     *   [MdzErrorCode.ENTRYPOINT_UNRESOLVED], or a manifest error from [manifest].
     */
    @Throws(MdzException::class)
    public fun entryPoint(): String = EntryPoints.resolve(files, manifest()?.json)

    /**
     * The archive's mode: [MdzMode.DOCUMENT] when there is no manifest or it declares none.
     * @throws MdzException [MdzErrorCode.MODE_UNSUPPORTED] for an unrecognized value, or a manifest error.
     */
    @Throws(MdzException::class)
    public fun mode(): MdzMode = manifest()?.mode ?: MdzMode.DOCUMENT

    /**
     * Every Markdown file in the archive, in stored order. In project mode each is a separate
     * document that opens on its own (Section 5.7.3); nothing here joins them.
     */
    public fun documents(): List<String> = files.filter { MdzPaths.isMarkdown(it) }

    /** The cover image's stored path, or `null` when there is none, it is invalid, or the file is missing. */
    @Throws(MdzException::class)
    public fun cover(): String? = manifest()?.cover?.let { c -> entry(c)?.takeIf { it.isFile }?.path }

    /**
     * Resolves a standard Markdown link destination [href] written in the document [from]
     * (Section 9.3): splits off the fragment and query, percent-decodes each segment once,
     * resolves `.` and `..` from [from]'s directory and matches the result (NFC, case-sensitive).
     *
     * A link that climbs above the archive root is [MdzLink.Reason.UNSUPPORTED]: links outside
     * the archive and into other archives are optional and need a host filesystem. It is never
     * clamped at the root or mapped to a file inside the archive.
     */
    public fun resolveLink(href: String, from: String): MdzLink {
        if (MdzPaths.check(from) != null) throw IllegalArgumentException("Not a valid archive path: $from")
        return Links.resolve(href, from) { entry(it) }
    }

    /** Checks the whole archive and returns every finding (Section 14.2). Never throws. */
    public fun validate(): MdzValidationReport = Validation.run(this, zip)

    /** Starts a modification (R6). The archive itself never changes; save the editor's result. */
    @Throws(MdzException::class)
    public fun edit(): MdzEditor = MdzEditor(this)

    internal val rawBytes: ByteArray get() = bytes
    internal fun record(e: MdzEntry): ZipRecord = e.record
    internal fun rawData(e: MdzEntry): ByteArray = ZipReader.rawData(bytes, e.record)

    public companion object {
        /** The manifest's path. */
        public const val MANIFEST: String = "manifest.json"

        /**
         * Opens an archive from its bytes. The array is used as is, so don't change it afterwards.
         * @throws MdzException [MdzErrorCode.ZIP_INVALID], [MdzErrorCode.ZIP_ENCRYPTED],
         *   [MdzErrorCode.LIMIT_EXCEEDED], [MdzErrorCode.PATH_INVALID] (with a detail code),
         *   [MdzErrorCode.DUPLICATE_PATH] or [MdzErrorCode.PATH_CONFLICT].
         */
        @JvmStatic
        @Throws(MdzException::class)
        public fun open(bytes: ByteArray): MdzArchive = open(bytes, Limits.DEFAULT)

        /**
         * Reads the whole stream and opens it as an archive, for example from
         * `ContentResolver.openInputStream`. The stream is not closed.
         */
        @JvmStatic
        @Throws(MdzException::class)
        public fun open(input: InputStream): MdzArchive = open(readAll(input))

        /** Reads a file and opens it. A convenience over [open] with bytes. */
        @JvmStatic
        @Throws(MdzException::class)
        public fun open(file: File): MdzArchive {
            if (file.length() > Limits.MAX_ARCHIVE_BYTES) {
                fail(MdzErrorCode.LIMIT_EXCEEDED, "File is too large to open: ${file.length()} bytes")
            }
            return try {
                FileInputStream(file).use { open(it) }
            } catch (e: MdzException) {
                throw e
            } catch (e: IOException) {
                throw MdzException(MdzErrorCode.IO, "Can't read ${file.path}: ${e.message}", cause = e)
            }
        }

        /** Validates archive bytes, reporting an archive that can't be opened as an error finding. Never throws. */
        @JvmStatic
        public fun validate(bytes: ByteArray): MdzValidationReport = try {
            open(bytes).validate()
        } catch (e: MdzException) {
            MdzValidationReport(listOf(MdzFinding(MdzSeverity.ERROR, e.code.code, e.message ?: "", e.path)))
        }

        internal fun open(bytes: ByteArray, limits: Limits): MdzArchive {
            val zip = ZipReader.read(bytes, limits)
            checkNames(zip.records)
            return MdzArchive(bytes, zip)
        }

        private fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size().toLong() + n > Limits.MAX_ARCHIVE_BYTES) {
                        fail(MdzErrorCode.LIMIT_EXCEEDED, "Stream is too large to open")
                    }
                    out.write(buf, 0, n)
                }
            } catch (e: MdzException) {
                throw e
            } catch (e: IOException) {
                throw MdzException(MdzErrorCode.IO, "Can't read the stream: ${e.message}", cause = e)
            }
            return out.toByteArray()
        }

        /** Section 5.4 for every name, then NFC duplicates and file/directory conflicts. */
        internal fun checkNames(records: List<ZipRecord>) {
            for (r in records) {
                val problem = MdzPaths.checkEntryName(r.name)
                if (problem != null) {
                    fail(MdzErrorCode.PATH_INVALID, "Entry name \"${r.name}\" breaks the path rules ($problem)", r.name, problem)
                }
            }
            checkNameSet(records.map { it.name })
        }

        /** Throws [MdzErrorCode.DUPLICATE_PATH] or [MdzErrorCode.PATH_CONFLICT] for a set of stored names. */
        internal fun checkNameSet(names: List<String>) {
            val seen = HashSet<String>()
            val files = HashMap<String, String>()
            val dirs = HashSet<String>()
            for (name in names) {
                val n = MdzPaths.nfc(name)
                if (!seen.add(n)) fail(MdzErrorCode.DUPLICATE_PATH, "Two entries are named \"$name\" after NFC normalization", name)
                val isDir = n.endsWith("/")
                val path = n.removeSuffix("/")
                if (isDir) dirs.add(path) else files[path] = name
                var k = path.lastIndexOf('/')
                while (k > 0) {
                    dirs.add(path.substring(0, k))
                    k = path.lastIndexOf('/', k - 1)
                }
            }
            for ((path, name) in files) {
                if (path in dirs) fail(MdzErrorCode.PATH_CONFLICT, "\"$name\" is both a file and a directory", name)
            }
        }
    }
}
