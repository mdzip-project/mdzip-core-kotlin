package org.mdzip.core

import java.io.IOException

/**
 * Stable error codes. [code] is the string from the MDZip specification (Section 16.5 and the
 * shared fixtures), for example `ERR_ENTRYPOINT_MISSING`. Compare the enum value, or [code];
 * never parse an exception message.
 *
 * Codes marked *library* are not in the specification; they cover failures the specification
 * doesn't name, such as reading an entry that doesn't exist.
 */
public enum class MdzErrorCode(public val code: String) {
    /** Not a valid ZIP archive, damaged data, or a compression method other than 0 or 8. */
    ZIP_INVALID("ERR_ZIP_INVALID"),
    /** An entry is encrypted. */
    ZIP_ENCRYPTED("ERR_ZIP_ENCRYPTED"),
    /** An entry name breaks the path rules (Section 5.4). [MdzException.detail] names which one. */
    PATH_INVALID("ERR_PATH_INVALID"),
    /** Detail of [PATH_INVALID]: the path starts with `/`. */
    PATH_LEADING_SLASH("ERR_PATH_LEADING_SLASH"),
    /** Detail of [PATH_INVALID]: the path has a `..` segment. */
    PATH_TRAVERSAL("ERR_PATH_TRAVERSAL"),
    /** Detail of [PATH_INVALID]: the path has one of `\ : * ? " < > |`. */
    PATH_RESERVED_CHAR("ERR_PATH_RESERVED_CHAR"),
    /** Detail of [PATH_INVALID]: the path has a control character. */
    PATH_CONTROL_CHAR("ERR_PATH_CONTROL_CHAR"),
    /** Two entries are equal after NFC normalization. */
    DUPLICATE_PATH("ERR_DUPLICATE_PATH"),
    /** A file's path is also a directory of another entry. */
    PATH_CONFLICT("ERR_PATH_CONFLICT"),
    /** `manifest.json` is unreadable, or a critical field has the wrong type or value (Section 6.4). */
    MANIFEST_INVALID("ERR_MANIFEST_INVALID"),
    /** `spec.version` has a major version this library doesn't support. */
    VERSION_UNSUPPORTED("ERR_VERSION_UNSUPPORTED"),
    /** `mode` is a value this library doesn't recognize. */
    MODE_UNSUPPORTED("ERR_MODE_UNSUPPORTED"),
    /** No unambiguous entry point (Section 5.5, rule 4). */
    ENTRYPOINT_UNRESOLVED("ERR_ENTRYPOINT_UNRESOLVED"),
    /** The manifest's `entryPoint` is not a valid entry point path. */
    ENTRYPOINT_INVALID("ERR_ENTRYPOINT_INVALID"),
    /** The manifest's `entryPoint` is valid but names no file in the archive. */
    ENTRYPOINT_MISSING("ERR_ENTRYPOINT_MISSING"),
    /** A resource limit was exceeded: entry count, size, compression ratio, or more data than declared. */
    LIMIT_EXCEEDED("ERR_LIMIT_EXCEEDED"),

    /** *Library.* No file entry has this path. */
    ENTRY_NOT_FOUND("ERR_ENTRY_NOT_FOUND"),
    /** *Library.* The entry is not valid UTF-8. */
    TEXT_DECODING("ERR_TEXT_DECODING"),
    /** *Library.* The entry is a symbolic link or other special file, which is never read or followed. */
    SPECIAL_ENTRY("ERR_SPECIAL_ENTRY"),
    /** *Library.* An argument the caller passed is not acceptable. */
    INVALID_ARGUMENT("ERR_INVALID_ARGUMENT"),
    /** *Library.* Reading or writing a file or stream failed. */
    IO("ERR_IO");

    override fun toString(): String = code

    public companion object {
        /** Returns the value whose [code] is [code], or `null`. */
        @JvmStatic
        public fun fromCode(code: String): MdzErrorCode? = values().firstOrNull { it.code == code }
    }
}

/**
 * The exception every operation throws. Check [code]; [message] is for people.
 *
 * It extends [IOException], so Java callers handle it with the I/O they already guard.
 *
 * @property code what went wrong.
 * @property detail a more specific code: for [MdzErrorCode.PATH_INVALID], one of the path details.
 * @property path the archive path involved, when there is one.
 */
public class MdzException @JvmOverloads constructor(
    public val code: MdzErrorCode,
    message: String,
    public val path: String? = null,
    public val detail: MdzErrorCode? = null,
    cause: Throwable? = null,
) : IOException("${code.code}: $message", cause)

internal fun fail(code: MdzErrorCode, message: String, path: String? = null, detail: MdzErrorCode? = null): Nothing =
    throw MdzException(code, message, path, detail)
