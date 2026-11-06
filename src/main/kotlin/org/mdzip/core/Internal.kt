package org.mdzip.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Resource limits applied when reading (Section 13.1). They are fixed; each is no looser than
 * the reference limits in the specification's `resource-limit-cases.json`.
 */
internal class Limits(
    val maxEntries: Int = 20_000,
    val maxEntryBytes: Long = 256L shl 20,
    val maxTotalBytes: Long = 1L shl 30,
    val maxCompressionRatio: Long = 1000,
    val ratioAppliesAboveBytes: Long = 1L shl 20,
) {
    companion object {
        val DEFAULT = Limits()

        /** The largest archive accepted from a stream or file: the largest Java byte array. */
        const val MAX_ARCHIVE_BYTES: Int = Int.MAX_VALUE - 8
    }
}

internal object Text {
    /** Decodes UTF-8, failing on malformed or unmappable input. */
    fun decodeUtf8Strict(bytes: ByteArray, path: String? = null): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw MdzException(MdzErrorCode.TEXT_DECODING, "Not valid UTF-8 text", path, cause = e)
        }
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        decodeUtf8Strict(bytes); true
    } catch (e: MdzException) {
        false
    }

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** Converts CRLF and lone CR to LF. */
    fun toLf(s: String): String = if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

    fun isAscii(s: String): Boolean = s.all { it.code < 0x80 }

    /** The current time as an RFC 3339 UTC datetime, without java.time (Android API 26). */
    fun nowRfc3339(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    /** A display title from the first `# ` heading, or the file name without its extension. */
    fun deriveTitle(entryPoint: String, markdown: String?): String {
        if (markdown != null) {
            for (raw in markdown.lineSequence()) {
                val line = raw.trimStart(' ')
                if (line.startsWith("# ")) {
                    val t = line.substring(2).trim().trimEnd('#').trim()
                    if (t.isNotEmpty()) return t
                }
            }
        }
        val name = entryPoint.substring(entryPoint.lastIndexOf('/') + 1)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
