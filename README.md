# mdzip-core-kotlin

Read, write and validate **MDZip** (`.mdz`) archives from Kotlin and Java, on Android and the JVM. An `.mdz` file is a ZIP archive holding one or more Markdown documents, the files they use and a `manifest.json`.

This library implements the [MDZip specification](https://github.com/mdzip-project/mdzip-spec) 1.2.1 and the required operations (R1 to R7) of the [core library contract](https://github.com/mdzip-project/.github/blob/main/docs/core-contract.md). It does not render Markdown; your app does that. Status: 0.1.0, on Maven Central. CI runs the tests on Java 11 and 17 and on Android emulators from API 21 to 35.

- Plain Kotlin/JVM library compiled to **Java 8 bytecode**. Works in Android apps without Android-specific packaging.
- Runs on **Android 5.0 (API 21) and later**: the build checks the compiled classes against the API 21 signature, so nothing from `java.nio.file`, `java.time`, `java.util.Base64` or other API 24+/26+ classes is used.
- **No dependencies** beyond `kotlin-stdlib`. ZIP reading and writing are this library's own, on top of `java.util.zip.Inflater`, `Deflater` and `CRC32` (see [ZIP library](#zip-library)).

## Install

From Maven Central:

```kotlin
dependencies {
    implementation("org.mdzip:mdzip-core:0.1.0")
}
```

Maven: `<dependency><groupId>org.mdzip</groupId><artifactId>mdzip-core</artifactId><version>0.1.0</version></dependency>`.

## Quick usage

```kotlin
import org.mdzip.core.*

// Read: from a byte array, an InputStream or a File.
val archive = context.contentResolver.openInputStream(uri)!!.use { MdzArchive.open(it) }
val entry = archive.entryPoint()                     // "index.md" (Section 5.5)
val markdown = archive.readText(entry)               // strict UTF-8
val logo = archive.readBytes("images/logo.png")
val title = archive.manifest()?.title
val mode = archive.mode()                            // MdzMode.DOCUMENT or MdzMode.PROJECT

when (val link = archive.resolveLink("../images/logo.png#x", from = "chapters/intro.md")) {
    is MdzLink.Internal -> println("${link.path} #${link.fragment}")
    is MdzLink.External -> println("open in a browser: ${link.url}")
    is MdzLink.Unresolved -> println("broken link: ${link.reason}")
}

for (finding in archive.validate().findings) println("${finding.severity} ${finding.code} ${finding.message}")

// Create
val bytes = MdzBuilder()
    .setTitle("My Guide")
    .setApplication("My App", "1.0")
    .addText("index.md", "# My Guide\n")
    .addBytes("images/logo.png", png)
    .toByteArray()                                   // or writeTo(outputStream) / writeTo(file)

// Modify
val editor = archive.edit()
editor.setText("notes.md", "# Notes\n")
editor.remove("old.md")
editor.manifest.put("title", "Second edition")       // any field, including unknown ones
contentResolver.openOutputStream(uri, "wt")!!.use { editor.writeTo(it) }
```

From Java the same calls work as static and instance methods:

```java
MdzArchive archive = MdzArchive.open(inputStream);
try {
    String entry = archive.entryPoint();
} catch (MdzException e) {
    if (e.getCode() == MdzErrorCode.ENTRYPOINT_MISSING) { /* ... */ }
}
byte[] bytes = new MdzBuilder().addText("index.md", "# Hi\n").toByteArray();
```

Every failure is an `MdzException` (a subclass of `IOException`). Check `e.code`, an `MdzErrorCode` whose `code` is the specification's string, such as `ERR_ENTRYPOINT_MISSING`; the message is for people. For a bad entry name `code` is `PATH_INVALID` and `detail` says which rule, for example `PATH_TRAVERSAL`.

### Android notes

- Android apps get files as streams (`ContentResolver.openInputStream`). `MdzArchive.open(InputStream)` reads the stream into memory and does not close it. `open(File)` is a convenience.
- Saving: `writeTo(OutputStream)` builds the whole archive in memory first, so a failed save (for example an edit that would remove the entry point) writes nothing to the stream. Open the output stream only after `toByteArray()` succeeds if you want to be sure the destination is never truncated. `writeTo(File)` writes a temporary file next to the target and renames it, so a failure leaves the existing file as it was.
- Archives are held in memory; the largest is about 2 GB, and the read limits below apply.
- `MdzArchive` is immutable and safe to share between threads. `MdzBuilder` and `MdzEditor` are not thread-safe.

## R1 to R7 and the public API

| ID | Operation | Public API |
|---|---|---|
| R1 | Open/read an archive | `MdzArchive.open(ByteArray)`, `open(InputStream)`, `open(File)`. The archive is held in memory; nothing is extracted. Opening checks the ZIP structure, the declared sizes against the limits, and every entry name. |
| R2 | Inspect entries and metadata | `entries` (`MdzEntry`: `path`, `isFile`, `isDirectory`, `isSymbolicLink`, `isSpecial`, `size`, `compressedSize`, `method`), `files`, `directories`, `specialEntries`, `contains(path)`, `entry(path)`, `readBytes(path)`, `readText(path)` (strict UTF-8, `ERR_TEXT_DECODING`), `nearMatch(path)` (a "did you mean" hint, never used as a match), `hasManifest`, `manifest()` (`MdzManifest`), `entryPoint()`, `mode()`, `documents()`, `cover()` |
| R3 | Resolve a link | `resolveLink(href, from)` returns `MdzLink.Internal` (stored path and fragment), `MdzLink.External` (a URL with a scheme) or `MdzLink.Unresolved` (with a `Reason`) |
| R4 | Validate | `validate()` and `MdzArchive.validate(bytes)` return an `MdzValidationReport`: `isValid`, `errors`, `warnings`, `notes`, each an `MdzFinding` with a stable `code`. `MdzManifest.parse(...)` validates a manifest on its own; `MdzPaths.check(path)` checks an entry name. |
| R5 | Create | `MdzBuilder`: `addText`, `addBytes`, `addDirectory`, `setEntryPoint`, `setTitle`, `setMode`, `setLanguage`, `setDescription`, `setLicense`, `setCover`, `setAuthor`, `setApplication`, `manifest` (any other field), `toByteArray()`, `writeTo(OutputStream)`, `writeTo(File)` |
| R6 | Modify | `MdzArchive.edit()` returns an `MdzEditor`: `setText`, `setBytes`, `addDirectory`, `remove`, `setEntryPoint`, `setTitle`, `setMode`, `setApplication`, `manifest` (the full manifest JSON), `addManifestIfMissing`, `updateModified`, `symlinkPolicy`, `specialEntries`, `toByteArray()`, `writeTo(OutputStream)`, `writeTo(File)` |
| R7 | Safety | Checked in `open`, `readBytes` and the editor: entry names (Section 5.4), NFC duplicates and file/directory conflicts, compression methods other than 0 and 8, encryption, symbolic links, overlapping entry data and the resource limits below. Links never leave the archive. |

Links outside the archive and into other archives (Sections 9.4 and 9.5) are optional and need a host filesystem, which this library does not have. They resolve to `Unresolved(UNSUPPORTED)`: never clamped at the archive root and never mapped to a file inside the archive.

### What the library guarantees

- **Entry point.** A declared `entryPoint` that is malformed fails with `ERR_ENTRYPOINT_INVALID`, and one that names no file fails with `ERR_ENTRYPOINT_MISSING`, including a difference in case only. There is never a silent fallback to `index.md` or `README.md`. With no manifest, the Section 5.5 discovery rules apply, setting aside `README`, `AGENTS`, `LICENSE`, `CHANGELOG` and `CONTRIBUTING`.
- **Paths.** Matched case-sensitively after NFC normalization. Support file names and the `.md`/`.markdown` extensions are compared ASCII case-insensitively, without the default locale (tested under `tr-TR`).
- **Manifest.** Parsed by a strict RFC 8259 parser that reports duplicate member names (the last value wins). Wrong critical fields (`spec`, `spec.version`, `mode`, `entryPoint`) throw; wrong descriptive fields are ignored and listed in `ignoredFields`. These errors come from `manifest()`, `entryPoint()` and `mode()`, not from `open`, so a bad manifest can still be inspected.
- **Writing.** Only methods 0 and 8; the UTF-8 flag on every non-ASCII name; no data descriptors; no ZIP64 records unless the entry count, an offset or the directory size needs them. Entries are regular files (mode 0644) or directories. New names are NFC. Markdown added as text gets LF line endings; other files are stored as given. Every written archive is reopened and checked before it is returned.
- **Creating** always writes `manifest.json` with `spec.name`, `spec.version` (1.2.1), `producer.core`, `title` (from the first `# ` heading or the file name if you don't set one), `mode`, `entryPoint` and `created.when`.
- **Modifying.** Entries you don't change keep their stored path and their compressed bytes, which are checked (CRC and size) before they are copied. Every manifest member is kept, including unknown ones at any depth. The entry point keeps resolving to the same file: if an edit would change what discovery picks (such as adding `AGENTS.md` next to a lone `README.md`), `entryPoint` is written to keep it, and removing the entry point file fails unless you call `setEntryPoint`. A manifest is added to an archive that has none (`addManifestIfMissing`), and `modified.when` is updated when anything changed (`updateModified`). Saving without changes keeps every entry, the manifest included, byte for byte.
- **Symbolic links** in an archive being modified are kept and listed in `specialEntries` by default (`SymlinkPolicy.KEEP`); show them to the user, since the saved archive is still non-conforming. `STORE_AS_FILE` turns each into a regular file holding the link text, and `REMOVE` drops them. They are never followed in any mode.

## Error codes

| Code | Meaning |
|---|---|
| `ERR_ZIP_INVALID` | Not a ZIP, damaged data, bad CRC, overlapping entries, size mismatch, or a method other than 0 or 8 |
| `ERR_ZIP_ENCRYPTED` | Encrypted entry |
| `ERR_PATH_INVALID` | Bad entry name; `detail` is `ERR_PATH_LEADING_SLASH`, `ERR_PATH_TRAVERSAL`, `ERR_PATH_RESERVED_CHAR`, `ERR_PATH_CONTROL_CHAR` or `ERR_PATH_INVALID` (empty or `.` segment) |
| `ERR_DUPLICATE_PATH`, `ERR_PATH_CONFLICT` | Equal names after NFC; a file that is also a directory |
| `ERR_MANIFEST_INVALID`, `ERR_VERSION_UNSUPPORTED`, `ERR_MODE_UNSUPPORTED` | Manifest problems (Section 6.4) |
| `ERR_ENTRYPOINT_INVALID`, `ERR_ENTRYPOINT_MISSING`, `ERR_ENTRYPOINT_UNRESOLVED` | Entry point problems (Section 5.5) |
| `ERR_LIMIT_EXCEEDED` | A resource limit, declared or actual |
| `ERR_ENTRY_NOT_FOUND`, `ERR_TEXT_DECODING`, `ERR_SPECIAL_ENTRY`, `ERR_INVALID_ARGUMENT`, `ERR_IO` | Library-specific (not in the specification) |

Validation warnings: `WARN_FIELD_IGNORED`, `WARN_DUPLICATE_MEMBER`, `WARN_SPEC_VERSION_OLDER`, `WARN_COVER_MISSING`, `WARN_SYMLINK_ENTRY`, `WARN_CASE_COLLISION`, `WARN_UTF8_FLAG_MISSING`, `WARN_PATH_NOT_NFC`, `WARN_LOCAL_NAME_MISMATCH`, `WARN_ZIP64_UNNEEDED`. Notes: `NOTE_NO_MANIFEST`, `NOTE_NO_SPEC_VERSION`. A missing manifest is a note, never a warning. Validation also reads every file (CRC and limits) and reports Markdown that is not UTF-8 as `ERR_TEXT_DECODING`.

## Safety limits

Fixed defaults (configurable limits are an optional extension and not offered), each no looser than the fixtures' reference limits:

| Limit | Default |
|---|---|
| Entries | 20,000 |
| One entry, uncompressed | 256 MiB |
| All entries, uncompressed | 1 GiB |
| Compression ratio (entries over 1 MiB) | 1000:1 |

Declared sizes are checked when opening, before any data is decompressed. Because they can lie, reads are checked too: decompression writes into a buffer of the declared size and fails with `ERR_LIMIT_EXCEEDED` as soon as the data produces one byte more, so an entry never returns more than it declared (and therefore the totals hold for the bytes actually decompressed). Input to the inflater is limited to the entry's compressed bytes; truncated or damaged data fails with `ERR_ZIP_INVALID`. Entries whose data overlaps another entry's are rejected, every central directory entry is listed or the archive is rejected, and the central directory name governs when a local header disagrees.

## ZIP library

The brief suggested Apache Commons Compress first. It doesn't fit:

| Need | Commons Compress (1.26 and later) |
|---|---|
| Random-access reading of the central directory | `ZipFile` needs `java.nio.channels.SeekableByteChannel`, which Android only has from API 24 |
| Older Android, few dependencies | It depends on `commons-io` and `commons-lang3`, and `ZipFile` is now built through commons-io's builders, which are based on `java.nio.file` (API 26) |
| Streams from `ContentResolver` | `ZipArchiveInputStream` reads local headers only, so it can't compare them with the central directory, can't see entries only the directory lists, and its `isUnixSymlink()` depends on the external attributes it doesn't have |

The JDK's `ZipInputStream` and `ZipFile` can't detect symbolic links (no external attributes), `ZipInputStream` can't check the central directory, and `ZipFile` needs a file path. `java.util.zip.ZipOutputStream` differs across Android versions (before API 24 Android used the Apache Harmony implementation), so its output can't be pinned down.

So the library writes only the missing parts and uses the JDK for the rest:

- `ZipReader.kt` reads the central directory (including ZIP64), validates every record and local header, applies the limits and rejects overlapping data. It uses `java.util.zip.Inflater` with an output buffer of the declared size and input limited to the entry's compressed bytes, then checks the CRC with `java.util.zip.CRC32`.
- `ZipWriter.kt` writes local headers, the central directory and the end record (ZIP64 only when needed), compressing with `java.util.zip.Deflater`, storing data that doesn't get smaller.
- `Json.kt` is a strict JSON parser and writer that reports duplicate member names. `org.json` is on Android but not on the JVM and doesn't report duplicates.

## Build and test

Requires JDK 11 or later; the Gradle wrapper (8.14.3) downloads everything else.

```sh
./gradlew build        # compile, tests, Android API 21 check (animalsnifferMain)
./gradlew test         # tests only; results in build/fixture-results.txt
```

On Windows with `JAVA_HOME` pointing elsewhere, set it for the build, for example `JAVA_HOME="C:/Program Files/Zulu/zulu-11" ./gradlew build`.

To run the checks on an Android phone or emulator, see [`android-test/README.md`](android-test/README.md).

The fixtures in `src/test/resources/fixtures` are copied from spec tag v1.2.1 with `node ../mdzip-spec/tools/sync-fixtures.mjs src/test/resources/fixtures --ref v1.2.1` and are not edited. A test checks every file against the SHA-256 in `SPEC-FIXTURES.json`.

### Fixture results

48 tests pass (19 consumer, 7 producer, 20 library, 2 from Java). Each fixture test also writes a `FIXTURE-RESULT` line.

| ID | Result | Note |
|---|---|---|
| T-001 to T-005 | passed | |
| T-006 | passed | `entrypoint-cases.json`, cases without `expectedError` |
| T-007 | passed | a missing manifest gives only a note |
| T-008 | passed | `entrypoint-cases.json`, cases with `expectedError` |
| T-009 | passed | `path-matching-cases.json`, by `MdzPaths.matches` and by archive lookup |
| T-010 | passed | default locale set to `tr-TR` |
| T-011 | passed | 42 cases from archive documents, run with outside and into-archive links unsupported (must be unresolved); 11 cases that start from a host note are not applicable |
| T-012 | passed | `manifest-cases.json`, by `MdzManifest.parse` and through an archive |
| T-013 | passed | `entry-set-cases.json` and `path-validation-cases.json`, by checking and by opening |
| T-014 | passed | rejected with `ERR_ZIP_INVALID` |
| T-015 | passed | project-capable: `mode()` is `PROJECT`, each document reads separately |
| T-016 | not applicable | the library does not render |
| T-017 | passed | never followed; listed in `specialEntries` and reported by `validate()` |
| T-018 | passed | forged size fails on read, high ratio and overlapping entries fail on open, all `ERR_LIMIT_EXCEEDED`; entry count checked with a limit of 5; overlap under the limits gives `ERR_ZIP_INVALID` |
| P-001 to P-003 | passed | P-003 adds the manifest even with `addManifestIfMissing = false` |
| P-004, P-005 | passed | five flows, each saved twice |
| P-006, P-007 | passed | P-007's checks run on every archive the producer tests write |
| P-008 | passed | kept and reported by default; `STORE_AS_FILE` and `REMOVE` on request |

`mdz validate` (CLI 2.0.0) reports "valid" with no warnings on the archives the tests write to `build/mdz-samples`: a created archive (application, author, cover, a Unicode name), a project-mode archive, `round-trip.mdz` after an edit, `minimal.mdz` after an edit (manifest added), a manifest-less archive plus `AGENTS.md`, Unicode names with a directory entry, and `symlink-entries.mdz` with its links stored as files. Set `MDZIP_OUT` to a folder to also write them there.

## Publishing

The build is set up for Maven Central as `org.mdzip:mdzip-core` with the [vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/) (Central Portal): main, sources and Dokka javadoc jars and a signed POM. Coordinates and POM fields are in `gradle.properties`. To publish, provide credentials and a signing key (for example in `~/.gradle/gradle.properties`: `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyPassword`) and run `./gradlew publishToMavenCentral`. That uploads to the Central Portal without releasing: check the deployment under **Deployments** on central.sonatype.com and click **Publish**, then tag the release (`git tag -a vX.Y.Z`). Bump `VERSION_NAME` in `gradle.properties` and `MdzLibrary.VERSION` together (a test checks they match). The `org.mdzip` namespace is verified on the Central Portal.

## What's left to do

- Tested on Android emulators only (API 21, 24, 29 and 35 in CI, all 14 device tests pass), not yet on a physical phone; [`android-test/README.md`](android-test/README.md) explains how.
- Archives are read and written in memory; there is no streaming or random-access file API.
- Optional extensions are not implemented: configurable limits, host link resolution (Sections 9.4 and 9.5), image analysis, workspace model, directory packing, MIME helpers and safe extraction to disk. `create-filter-cases.json` (a packing convenience) is not run.
- Names without the UTF-8 flag that aren't valid UTF-8 are decoded as CP437; there is no other legacy encoding support.
- `modified.by` is not set on edits.

## License

Apache License 2.0, as the other MDZip cores.
