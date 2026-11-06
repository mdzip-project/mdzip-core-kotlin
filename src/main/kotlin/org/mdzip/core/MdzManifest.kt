package org.mdzip.core

/** The interpretation mode of an archive (Section 5.7). */
public enum class MdzMode(public val value: String) {
    /** One publication, opened at its entry point. The default. */
    DOCUMENT("document"),
    /** A collection of independent documents. */
    PROJECT("project");

    override fun toString(): String = value

    public companion object {
        /** The mode for a manifest value (case-sensitive), or `null` if it isn't recognized. */
        @JvmStatic
        public fun fromValue(value: String): MdzMode? = values().firstOrNull { it.value == value }
    }
}

/** A person in `author`, `created.by` or `modified.by`. */
public class MdzPerson(public val name: String?, public val email: String?, public val url: String?)

/** A tool in `producer.application` or `producer.core`. */
public class MdzTool(public val name: String?, public val version: String?, public val url: String?)

/**
 * `created` or `modified`: an RFC 3339 datetime and optionally who made the change.
 * [legacy] is true for the plain-string form from draft 1.0.x.
 */
public class MdzTimestamp(public val `when`: String, public val by: MdzPerson?, public val legacy: Boolean)

/**
 * A parsed and validated `manifest.json` (Section 6).
 *
 * Critical fields (`spec`, `spec.version`, `mode`, `entryPoint`) that are wrong make [parse]
 * throw. Descriptive fields that are wrong are ignored: their getters return `null` and their
 * names are listed in [ignoredFields]. Every member, known or not, stays in [json].
 */
public class MdzManifest private constructor(
    json: JsonObject,
    /** JSON Pointers of member names that appeared more than once; the last value is used. */
    public val duplicateMembers: List<String>,
    /** Descriptive top-level fields with a wrong type or invalid value, which are ignored. */
    public val ignoredFields: List<String>,
) {
    private val raw: JsonObject = json

    /** A copy of the whole manifest, including members this library doesn't know. */
    public val json: JsonObject get() = raw.deepCopy()

    private fun str(key: String): String? = if (key in ignoredFields) null else raw.getString(key)

    /** `spec.version`, or `null` when absent. */
    public val specVersion: String? get() = raw.getObject("spec")?.getString("version")

    /** `spec.name`, or `null`. */
    public val specName: String? get() = if ("spec.name" in ignoredFields) null else raw.getObject("spec")?.getString("name")

    /** The declared mode, or [MdzMode.DOCUMENT] when the manifest has none. */
    public val mode: MdzMode get() = raw.getString("mode")?.let { MdzMode.fromValue(it) } ?: MdzMode.DOCUMENT

    /** The declared `entryPoint` as written (it may start with `./`), or `null` when absent. */
    public val entryPoint: String? get() = raw.getString("entryPoint")

    public val title: String? get() = str("title")
    public val language: String? get() = str("language")
    public val description: String? get() = str("description")
    public val version: String? get() = str("version")
    public val license: String? get() = str("license")

    /** `cover` without a leading `./`, or `null` when absent or invalid. It may still name a missing file. */
    public val cover: String? get() = str("cover")?.let { MdzPaths.normalizeReference(it) }

    public val keywords: List<String>
        get() = if ("keywords" in ignoredFields) emptyList()
        else (raw["keywords"] as? JsonArray)?.items?.map { (it as JsonString).value } ?: emptyList()

    public val author: MdzPerson? get() = if ("author" in ignoredFields) null else raw.getObject("author")?.let(::person)
    public val created: MdzTimestamp? get() = timestamp("created")
    public val modified: MdzTimestamp? get() = timestamp("modified")

    public val producerApplication: MdzTool?
        get() = if ("producer" in ignoredFields) null else raw.getObject("producer")?.getObject("application")?.let(::tool)
    public val producerCore: MdzTool?
        get() = if ("producer" in ignoredFields) null else raw.getObject("producer")?.getObject("core")?.let(::tool)

    private fun timestamp(key: String): MdzTimestamp? {
        if (key in ignoredFields) return null
        return when (val v = raw[key]) {
            is JsonString -> MdzTimestamp(v.value, null, true)
            is JsonObject -> MdzTimestamp(v.getString("when")!!, v.getObject("by")?.let(::person), false)
            else -> null
        }
    }

    public companion object {
        /**
         * Parses `manifest.json` bytes, which must be UTF-8.
         * @throws MdzException [MdzErrorCode.MANIFEST_INVALID], [MdzErrorCode.VERSION_UNSUPPORTED],
         *   [MdzErrorCode.MODE_UNSUPPORTED] or [MdzErrorCode.ENTRYPOINT_INVALID].
         */
        @JvmStatic
        @Throws(MdzException::class)
        public fun parse(bytes: ByteArray): MdzManifest = fromAnalysis(analyze(bytes))

        /** Parses `manifest.json` text. Throws like the byte version. */
        @JvmStatic
        @Throws(MdzException::class)
        public fun parse(text: String): MdzManifest = fromAnalysis(analyze(text))

        /** Validates a manifest held as JSON. Throws like [parse]. */
        @JvmStatic
        @Throws(MdzException::class)
        public fun fromJson(json: JsonObject): MdzManifest = fromAnalysis(analyze(json.deepCopy(), emptyList()))

        private fun fromAnalysis(a: ManifestAnalysis): MdzManifest {
            a.critical.firstOrNull()?.let { throw it }
            return MdzManifest(a.json!!, a.duplicates, a.ignored)
        }

        internal fun analyze(bytes: ByteArray): ManifestAnalysis {
            val text = try {
                Text.decodeUtf8Strict(bytes)
            } catch (e: MdzException) {
                return ManifestAnalysis.unreadable("manifest.json is not valid UTF-8")
            }
            return analyze(text)
        }

        internal fun analyze(text: String): ManifestAnalysis {
            val t = if (text.startsWith("﻿")) text.substring(1) else text
            val parsed = try {
                JsonParser(t).parseDocument()
            } catch (e: JsonParseException) {
                return ManifestAnalysis.unreadable("manifest.json is not valid JSON: ${e.message}")
            }
            val obj = parsed.value as? JsonObject
                ?: return ManifestAnalysis.unreadable("manifest.json must hold a JSON object")
            return analyze(obj, parsed.duplicates)
        }

        internal fun analyze(m: JsonObject, duplicates: List<String>): ManifestAnalysis {
            val critical = ArrayList<MdzException>()
            val ignored = ArrayList<String>()
            fun err(code: MdzErrorCode, msg: String) = critical.add(MdzException(code, msg, "manifest.json"))

            if (m.containsKey("spec")) {
                val spec = m["spec"]
                if (spec !is JsonObject) {
                    err(MdzErrorCode.MANIFEST_INVALID, "spec must be an object")
                } else {
                    if (spec.containsKey("version")) {
                        val v = spec["version"]
                        if (v !is JsonString || !Validators.isSemVer(v.value)) {
                            err(MdzErrorCode.MANIFEST_INVALID, "spec.version must be a Semantic Versioning string")
                        } else if (Validators.majorOf(v.value) > SUPPORTED_MAJOR) {
                            err(MdzErrorCode.VERSION_UNSUPPORTED,
                                "spec.version ${v.value} has a major version above $SUPPORTED_MAJOR")
                        }
                    }
                    if (spec.containsKey("name") && spec["name"] !is JsonString) ignored.add("spec.name")
                }
            }
            if (m.containsKey("mode")) {
                when (val mode = m["mode"]) {
                    is JsonString -> if (MdzMode.fromValue(mode.value) == null) {
                        err(MdzErrorCode.MODE_UNSUPPORTED, "mode \"${mode.value}\" is not recognized")
                    }
                    else -> err(MdzErrorCode.MANIFEST_INVALID, "mode must be a string")
                }
            }
            if (m.containsKey("entryPoint")) {
                val ep = m["entryPoint"]
                if (ep !is JsonString || MdzPaths.normalizeEntryPoint(ep.value) == null) {
                    err(MdzErrorCode.ENTRYPOINT_INVALID, "entryPoint must be a relative path to a .md or .markdown file")
                }
            }

            for (key in m.keys) {
                val v = m[key]!!
                val ok = when (key) {
                    "title" -> v is JsonString && v.value.isNotEmpty()
                    "description", "version", "license" -> v is JsonString
                    "language" -> v is JsonString && Validators.isLanguageTag(v.value)
                    "author" -> v is JsonObject && Validators.isPerson(v)
                    "producer" -> v is JsonObject && Validators.isProducer(v)
                    "created", "modified" -> Validators.isTimestamp(v)
                    "keywords" -> v is JsonArray && v.items.all { it is JsonString }
                    "cover" -> v is JsonString && MdzPaths.normalizeReference(v.value) != null
                    else -> true // critical fields are checked above; unknown fields are never validated
                }
                if (!ok) ignored.add(key)
            }
            return ManifestAnalysis(m, duplicates, critical, ignored, unreadable = false)
        }

        /** The highest `spec.version` major version this library reads. */
        public const val SUPPORTED_MAJOR: Int = 1

        private fun person(o: JsonObject) = MdzPerson(o.getString("name"), o.getString("email"), o.getString("url"))
        private fun tool(o: JsonObject) = MdzTool(o.getString("name"), o.getString("version"), o.getString("url"))
    }
}

internal class ManifestAnalysis(
    val json: JsonObject?,
    val duplicates: List<String>,
    val critical: List<MdzException>,
    val ignored: List<String>,
    val unreadable: Boolean,
) {
    companion object {
        fun unreadable(message: String) = ManifestAnalysis(
            null, emptyList(),
            listOf(MdzException(MdzErrorCode.MANIFEST_INVALID, message, "manifest.json")),
            emptyList(), unreadable = true,
        )
    }
}

internal object Validators {
    private val SEMVER = Regex(
        "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?"
    )
    private val RFC3339 = Regex(
        "(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?([Zz]|([+-])(\\d{2}):(\\d{2}))"
    )
    private val LANGUAGE = Regex("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*")

    fun isSemVer(s: String) = SEMVER.matches(s)

    /** The major version of a valid SemVer string; huge values compare as larger than any Int. */
    fun majorOf(s: String): Int {
        val major = s.substring(0, s.indexOf('.'))
        return if (major.length > 9) Int.MAX_VALUE else major.toInt()
    }

    fun isLanguageTag(s: String) = LANGUAGE.matches(s)

    fun isRfc3339(s: String): Boolean {
        val m = RFC3339.matchEntire(s) ?: return false
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (month !in 1..12 || day < 1 || day > days[month - 1]) return false
        if (g[4].toInt() > 23 || g[5].toInt() > 59 || g[6].toInt() > 60) return false
        if (g[9].isNotEmpty() && (g[10].toInt() > 23 || g[11].toInt() > 59)) return false
        return true
    }

    private fun stringsOnly(o: JsonObject, vararg keys: String) = keys.all { !o.containsKey(it) || o[it] is JsonString }

    fun isPerson(o: JsonObject) = stringsOnly(o, "name", "email", "url")

    fun isProducer(o: JsonObject): Boolean {
        for (key in arrayOf("application", "core")) {
            if (!o.containsKey(key)) continue
            val t = o[key] as? JsonObject ?: return false
            if (!stringsOnly(t, "name", "version", "url")) return false
        }
        return true
    }

    fun isTimestamp(v: JsonValue): Boolean = when (v) {
        is JsonString -> isRfc3339(v.value)
        is JsonObject -> {
            val w = v["when"]
            val by = v["by"]
            w is JsonString && isRfc3339(w.value) && (by == null || (by is JsonObject && isPerson(by)))
        }
        else -> false
    }
}
