package org.mdzip.core

import java.text.Normalizer

/**
 * Path rules from the specification: entry name checks (Section 5.4), matching (Section 5.4.1)
 * and the fixed ASCII names (Markdown extensions and support files, Section 5.5).
 *
 * Matching is case-sensitive after NFC normalization. The only case-insensitive comparisons are
 * ASCII-only and never use the default locale, so they behave the same under a Turkish locale.
 */
public object MdzPaths {
    private const val RESERVED = "\\:*?\"<>|"
    private val SUPPORT_NAMES = arrayOf("readme", "agents", "license", "changelog", "contributing")

    /**
     * Checks a file path against Section 5.4. Returns `null` when it is valid, otherwise the
     * specific problem: [MdzErrorCode.PATH_LEADING_SLASH], [MdzErrorCode.PATH_TRAVERSAL],
     * [MdzErrorCode.PATH_RESERVED_CHAR], [MdzErrorCode.PATH_CONTROL_CHAR] or
     * [MdzErrorCode.PATH_INVALID] (empty, empty segment or `.` segment).
     */
    @JvmStatic
    public fun check(path: String): MdzErrorCode? {
        if (path.isEmpty()) return MdzErrorCode.PATH_INVALID
        if (path[0] == '/') return MdzErrorCode.PATH_LEADING_SLASH
        for (c in path) {
            if (c < ' ' || c == '\u007f') return MdzErrorCode.PATH_CONTROL_CHAR
        }
        for (c in path) {
            if (RESERVED.indexOf(c) >= 0) return MdzErrorCode.PATH_RESERVED_CHAR
        }
        var invalid = false
        for (segment in path.split('/')) {
            if (segment == "..") return MdzErrorCode.PATH_TRAVERSAL
            if (segment.isEmpty() || segment == ".") invalid = true
        }
        return if (invalid) MdzErrorCode.PATH_INVALID else null
    }

    /** True when [path] is a valid file path (see [check]). */
    @JvmStatic
    public fun isValid(path: String): Boolean = check(path) == null

    /** Checks a stored entry name: a directory entry ends in `/`, the rest follows [check]. */
    internal fun checkEntryName(name: String): MdzErrorCode? =
        if (name.endsWith("/") && name.length > 1) check(name.substring(0, name.length - 1)) else check(name)

    /** The NFC form of [path]. */
    @JvmStatic
    public fun nfc(path: String): String =
        if (Normalizer.isNormalized(path, Normalizer.Form.NFC)) path else Normalizer.normalize(path, Normalizer.Form.NFC)

    /** True when [a] and [b] name the same entry: equal after NFC normalization, case-sensitive. */
    @JvmStatic
    public fun matches(a: String, b: String): Boolean = a == b || nfc(a) == nfc(b)

    /** True when [path] ends in `.md` or `.markdown`, compared ASCII case-insensitively. */
    @JvmStatic
    public fun isMarkdown(path: String): Boolean {
        val name = path.substring(path.lastIndexOf('/') + 1)
        return asciiEndsWithIgnoreCase(name, ".md") || asciiEndsWithIgnoreCase(name, ".markdown")
    }

    /**
     * True when [path] is a root-level support file (Section 5.5): `README`, `AGENTS`, `LICENSE`,
     * `CHANGELOG` or `CONTRIBUTING` with a `.md` or `.markdown` extension, ASCII case-insensitive.
     */
    @JvmStatic
    public fun isSupportFile(path: String): Boolean {
        if (path.indexOf('/') >= 0) return false
        val base = when {
            asciiEndsWithIgnoreCase(path, ".markdown") -> path.substring(0, path.length - 9)
            asciiEndsWithIgnoreCase(path, ".md") -> path.substring(0, path.length - 3)
            else -> return false
        }
        return SUPPORT_NAMES.any { asciiEqualsIgnoreCase(base, it) }
    }

    /**
     * Checks a manifest `entryPoint` value (Section 5.5): a valid path to a Markdown file, with a
     * single leading `./` allowed. Returns the path to match (without `./`), or `null` if invalid.
     */
    @JvmStatic
    public fun normalizeEntryPoint(value: String): String? {
        val path = normalizeReference(value) ?: return null
        return if (isMarkdown(path)) path else null
    }

    /** Like [normalizeEntryPoint] for any file (used for `cover`). */
    internal fun normalizeReference(value: String): String? {
        val path = if (value.startsWith("./")) value.substring(2) else value
        if (path.endsWith("/")) return null
        return if (check(path) == null) path else null
    }

    /** Unicode default case folding, close enough for "did you mean" hints (never for matching). */
    internal fun caseFold(s: String): String =
        nfc(s).uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT)

    internal fun asciiLower(c: Char): Char = if (c in 'A'..'Z') (c + 32) else c

    internal fun asciiEqualsIgnoreCase(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) if (asciiLower(a[i]) != asciiLower(b[i])) return false
        return true
    }

    internal fun asciiEndsWithIgnoreCase(s: String, suffix: String): Boolean =
        s.length >= suffix.length && asciiEqualsIgnoreCase(s.substring(s.length - suffix.length), suffix)
}
