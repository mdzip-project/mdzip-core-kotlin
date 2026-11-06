package org.mdzip.core

/** How serious a [MdzFinding] is. */
public enum class MdzSeverity {
    /** The archive is not conforming; [code] is an `ERR_` code. */
    ERROR,
    /** Conforming but worth fixing; [code] starts with `WARN_`. */
    WARNING,
    /** Information only, such as a missing (optional) manifest; [code] starts with `NOTE_`. */
    INFO,
}

/**
 * One validation result. [code] is stable: an `ERR_` code from [MdzErrorCode] for errors, or a
 * `WARN_` / `NOTE_` code listed in the README. [message] is for people; [path] is the entry or
 * manifest field involved, when there is one.
 */
public class MdzFinding(
    public val severity: MdzSeverity,
    public val code: String,
    public val message: String,
    public val path: String?,
) {
    /** The error code as an enum, for errors; `null` for warnings and notes. */
    public val errorCode: MdzErrorCode? get() = MdzErrorCode.fromCode(code)

    override fun toString(): String = "$severity $code${path?.let { " [$it]" } ?: ""}: $message"
}

/** The result of [MdzArchive.validate]. */
public class MdzValidationReport(public val findings: List<MdzFinding>) {
    /** True when there are no errors (warnings and notes are allowed). */
    public val isValid: Boolean get() = errors.isEmpty()
    public val errors: List<MdzFinding> get() = findings.filter { it.severity == MdzSeverity.ERROR }
    public val warnings: List<MdzFinding> get() = findings.filter { it.severity == MdzSeverity.WARNING }
    public val notes: List<MdzFinding> get() = findings.filter { it.severity == MdzSeverity.INFO }

    /** True when any finding has [code]. */
    public fun hasCode(code: String): Boolean = findings.any { it.code == code }

    /** True when any finding has [code]. */
    public fun hasCode(code: MdzErrorCode): Boolean = hasCode(code.code)

    override fun toString(): String = findings.joinToString("\n")
}

internal object Validation {
    fun run(a: MdzArchive, zip: ZipDirectory): MdzValidationReport {
        val out = ArrayList<MdzFinding>()
        fun add(sev: MdzSeverity, code: String, msg: String, path: String? = null) = out.add(MdzFinding(sev, code, msg, path))
        fun error(e: MdzException) = add(MdzSeverity.ERROR, e.code.code, e.message!!.substringAfter(": "), e.path)

        // ZIP structure and names.
        if (zip.usesZip64 && !zip.zip64Needed) {
            add(MdzSeverity.WARNING, "WARN_ZIP64_UNNEEDED", "The archive uses ZIP64 records although every value fits without them (Section 4)")
        }
        for (e in a.entries) {
            val r = a.record(e)
            if (!Text.isAscii(e.path) && !r.hasUtf8Flag) {
                add(MdzSeverity.WARNING, "WARN_UTF8_FLAG_MISSING", "Non-ASCII name without the UTF-8 flag (bit 11)", e.path)
            }
            if (MdzPaths.nfc(e.path) != e.path) {
                add(MdzSeverity.WARNING, "WARN_PATH_NOT_NFC", "Name is not in Unicode NFC", e.path)
            }
            if (!r.localNameMatches) {
                add(MdzSeverity.WARNING, "WARN_LOCAL_NAME_MISMATCH", "Local header name differs from the central directory, which is used", e.path)
            }
            if (e.isSpecial) {
                // Reported, as the other MDZip cores and the mdz CLI do; the entry is never read or followed.
                val what = if (e.isSymbolicLink) "a symbolic link" else "a special file"
                add(MdzSeverity.WARNING, "WARN_SYMLINK_ENTRY", "Entry is $what; it is ignored and never followed (Sections 4, 13.1)", e.path)
            }
        }
        val folded = HashMap<String, String>()
        for (e in a.entries) {
            val key = MdzPaths.caseFold(e.path)
            val other = folded[key]
            if (other != null && MdzPaths.nfc(other) != MdzPaths.nfc(e.path)) {
                add(MdzSeverity.WARNING, "WARN_CASE_COLLISION",
                    "\"${e.path}\" and \"$other\" differ only in case and collide on case-insensitive file systems", e.path)
            } else {
                folded[key] = e.path
            }
        }

        // Entry data (CRC, declared sizes) and Markdown encoding.
        for (path in a.files) {
            try {
                val data = a.readBytes(path)
                if (MdzPaths.isMarkdown(path) && !Text.isValidUtf8(data)) {
                    add(MdzSeverity.ERROR, MdzErrorCode.TEXT_DECODING.code, "Markdown file is not valid UTF-8 (Section 7.1)", path)
                }
            } catch (e: MdzException) {
                error(e)
            }
        }

        // Manifest (Section 6.4).
        var manifestJson: JsonObject? = null
        var manifestReadable = true
        if (!a.hasManifest) {
            add(MdzSeverity.INFO, "NOTE_NO_MANIFEST", "No manifest.json; the entry point is discovered (valid for reading)")
        } else {
            val analysis = try {
                MdzManifest.analyze(a.readBytes(MdzArchive.MANIFEST))
            } catch (e: MdzException) {
                null
            }
            if (analysis == null) {
                manifestReadable = false // the read error is already reported above
            } else {
                manifestReadable = !analysis.unreadable
                manifestJson = analysis.json
                for (e in analysis.critical) error(e)
                for (f in analysis.ignored) {
                    add(MdzSeverity.WARNING, "WARN_FIELD_IGNORED", "Descriptive field \"$f\" has a wrong type or value and is ignored", f)
                }
                for (p in analysis.duplicates) {
                    add(MdzSeverity.WARNING, "WARN_DUPLICATE_MEMBER", "Duplicate member name; the last value is used", p)
                }
                val m = analysis.json
                if (m != null) {
                    val version = m.getObject("spec")?.getString("version")
                    if (m.getObject("spec")?.containsKey("version") != true) {
                        add(MdzSeverity.INFO, "NOTE_NO_SPEC_VERSION", "manifest.json has no spec.version; treated as baseline 1.x")
                    } else if (version != null && Validators.isSemVer(version) && Validators.majorOf(version) < MdzManifest.SUPPORTED_MAJOR) {
                        add(MdzSeverity.WARNING, "WARN_SPEC_VERSION_OLDER", "spec.version $version is an older major version")
                    }
                    val cover = m.getString("cover")?.let { MdzPaths.normalizeReference(it) }
                    if (cover != null && "cover" !in analysis.ignored && a.entry(cover)?.isFile != true) {
                        add(MdzSeverity.WARNING, "WARN_COVER_MISSING", "cover \"$cover\" is not in the archive and is ignored", cover)
                    }
                }
            }
        }

        // Entry point (Section 5.5). A manifest's own entryPoint error is already reported.
        if (manifestReadable) {
            try {
                EntryPoints.resolve(a.files, manifestJson)
            } catch (e: MdzException) {
                if (!(e.code == MdzErrorCode.ENTRYPOINT_INVALID && out.any { it.code == e.code.code })) error(e)
            }
        }
        return MdzValidationReport(out)
    }
}
