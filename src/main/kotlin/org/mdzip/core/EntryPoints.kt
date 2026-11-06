package org.mdzip.core

/** Entry point discovery (Section 5.5) over a set of file paths. */
internal object EntryPoints {
    /**
     * Resolves the entry point.
     * @param files the archive's regular files (stored paths; never directories or special entries).
     * @param manifest the manifest as JSON, or `null` when there is none.
     */
    fun resolve(files: Collection<String>, manifest: JsonObject?): String {
        val byNfc = HashMap<String, String>()
        for (f in files) byNfc[MdzPaths.nfc(f)] = f

        // Rule 1: a declared entryPoint is used, or is an error. Never a fallback.
        if (manifest != null && manifest.containsKey("entryPoint")) {
            val value = manifest["entryPoint"]
            val path = (value as? JsonString)?.let { MdzPaths.normalizeEntryPoint(it.value) }
                ?: fail(MdzErrorCode.ENTRYPOINT_INVALID,
                    "manifest entryPoint ${value?.toJson()} is not a relative path to a .md or .markdown file")
            byNfc[MdzPaths.nfc(path)]?.let { return it }
            val hint = nearMatch(files, path)?.let { " (did you mean \"$it\"?)" } ?: ""
            fail(MdzErrorCode.ENTRYPOINT_MISSING, "manifest entryPoint \"$path\" is not in the archive$hint", path)
        }

        // Rule 2: index.md at the root.
        byNfc["index.md"]?.let { return it }

        // Rule 3: root-level Markdown files, setting aside support files.
        val root = files.filter { it.indexOf('/') < 0 && MdzPaths.isMarkdown(it) }
        val content = root.filter { !MdzPaths.isSupportFile(it) }
        if (content.size == 1) return content[0]
        if (content.isEmpty() && root.size == 1) return root[0]

        // Rule 4: never pick one arbitrarily.
        val message = if (root.isEmpty()) "the archive has no Markdown file at its root"
        else "no unambiguous entry point among ${root.sorted().joinToString(", ")}; add manifest.json with an entryPoint"
        fail(MdzErrorCode.ENTRYPOINT_UNRESOLVED, message)
    }

    /** A file equal to [path] under case folding but not exactly: a hint, never a match. */
    fun nearMatch(files: Collection<String>, path: String): String? {
        val folded = MdzPaths.caseFold(path)
        val exact = MdzPaths.nfc(path)
        return files.firstOrNull { MdzPaths.nfc(it) != exact && MdzPaths.caseFold(it) == folded }
    }
}
