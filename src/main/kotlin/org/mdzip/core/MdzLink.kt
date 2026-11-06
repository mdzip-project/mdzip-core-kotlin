package org.mdzip.core

/**
 * The result of [MdzArchive.resolveLink]: an entry inside the archive, an external URL, or
 * unresolved. Check [kind], or use `is` / `instanceof` on the subclasses.
 */
public sealed class MdzLink {
    /** What kind of result this is. */
    public abstract val kind: Kind

    /** The part after the first `#`, without the `#`; `null` when the link has none. */
    public abstract val fragment: String?

    public enum class Kind { INTERNAL, EXTERNAL, UNRESOLVED }

    /** Why a link is unresolved. Informative: the fixtures check only that it is unresolved. */
    public enum class Reason {
        /** No file in the archive has the resolved path (case and NFC matter), or it names a directory. */
        NOT_FOUND,
        /** Not a valid relative link: starts with `/`, contains `\`, or has bad percent-encoding. */
        INVALID,
        /**
         * The link climbs above the archive root. Links outside the archive and into other archives
         * (Sections 9.4 and 9.5) are optional and not supported here; they are never clamped at the root.
         */
        UNSUPPORTED,
        /** The path names a symbolic link or other special entry, which is never followed. */
        SPECIAL_ENTRY,
    }

    /** An entry inside the archive. [path] is the stored entry path. */
    public class Internal(public val path: String, override val fragment: String?) : MdzLink() {
        override val kind: Kind get() = Kind.INTERNAL
        override fun toString(): String = "Internal($path${fragment?.let { "#$it" } ?: ""})"
    }

    /** A URL with a scheme, such as `https:` or `mailto:`. It is not resolved or loaded. */
    public class External(public val url: String) : MdzLink() {
        override val kind: Kind get() = Kind.EXTERNAL
        override val fragment: String? get() = url.indexOf('#').let { if (it < 0) null else url.substring(it + 1) }
        override fun toString(): String = "External($url)"
    }

    /** The link can't be resolved. Show it as broken; never substitute another target. */
    public class Unresolved(public val reason: Reason, override val fragment: String?) : MdzLink() {
        override val kind: Kind get() = Kind.UNRESOLVED
        override fun toString(): String = "Unresolved($reason)"
    }
}

/** Link resolution inside an archive (Section 9.3). */
internal object Links {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    fun resolve(href: String, from: String, lookup: (String) -> MdzEntry?): MdzLink {
        if (SCHEME.containsMatchIn(href)) return MdzLink.External(href)

        // 1. Fragment after the first '#'; then drop any ?query.
        val hash = href.indexOf('#')
        val fragment = if (hash >= 0) href.substring(hash + 1) else null
        var dest = if (hash >= 0) href.substring(0, hash) else href
        val q = dest.indexOf('?')
        if (q >= 0) dest = dest.substring(0, q)

        // 2. Nothing left: the referencing document itself.
        if (dest.isEmpty()) return MdzLink.Internal(from, fragment)

        // 3. Root-relative and backslash links are not valid relative links.
        if (dest.startsWith("/") || dest.indexOf('\\') >= 0) return MdzLink.Unresolved(MdzLink.Reason.INVALID, fragment)

        // 4. Split, then percent-decode each segment exactly once.
        val raw = dest.split('/')
        val segments = ArrayList<String>(raw.size)
        for (s in raw) {
            val decoded = percentDecode(s) ?: return MdzLink.Unresolved(MdzLink.Reason.INVALID, fragment)
            if (decoded.any { it == '/' || it == '\\' || it < ' ' || it == '\u007f' }) {
                return MdzLink.Unresolved(MdzLink.Reason.INVALID, fragment)
            }
            segments.add(decoded)
        }

        // 5. Walk from the referencing file's directory.
        val stack = ArrayList(from.split('/').dropLast(1))
        for (seg in segments) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    // 7. Leaving the archive: not supported, and never clamped at the root.
                    if (stack.isEmpty()) return MdzLink.Unresolved(MdzLink.Reason.UNSUPPORTED, fragment)
                    stack.removeAt(stack.size - 1)
                }
                else -> stack.add(seg)
            }
        }
        // A destination ending in '/' names a directory; inside an archive that is unresolved.
        if (dest.endsWith("/") || stack.isEmpty()) return MdzLink.Unresolved(MdzLink.Reason.NOT_FOUND, fragment)

        // 6. Match against the archive's entries (NFC, case-sensitive).
        val entry = lookup(stack.joinToString("/")) ?: return MdzLink.Unresolved(MdzLink.Reason.NOT_FOUND, fragment)
        return when {
            entry.isSpecial -> MdzLink.Unresolved(MdzLink.Reason.SPECIAL_ENTRY, fragment)
            entry.isDirectory -> MdzLink.Unresolved(MdzLink.Reason.NOT_FOUND, fragment)
            else -> MdzLink.Internal(entry.path, fragment)
        }
    }

    /** Decodes %XX escapes as UTF-8; `null` on bad escapes or bytes that are not UTF-8. */
    private fun percentDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                // Copy literal text as UTF-8, keeping surrogate pairs together.
                val len = if (Character.isHighSurrogate(c) && i + 1 < s.length) 2 else 1
                val b = Text.utf8(s.substring(i, i + len))
                bytes.write(b, 0, b.size)
                i += len
            }
        }
        return try { Text.decodeUtf8Strict(bytes.toByteArray()) } catch (e: MdzException) { null }
    }
}
