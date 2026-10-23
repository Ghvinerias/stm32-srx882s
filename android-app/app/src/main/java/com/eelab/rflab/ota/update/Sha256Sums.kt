package com.eelab.rflab.ota.update

/**
 * One parsed line of a `SHA256SUMS` manifest (the format `sha256sum -b`
 * writes): the lowercase SHA-256 of [fileName].
 *
 * The hash hex is normalised to lowercase so comparisons against digests
 * computed by the app need no case handling.
 */
data class Sha256SumsEntry(
    /** The file's path/document name exactly as written in the SUMS line. */
    val fileName: String,
    /** SHA-256 of the file, 64 lowercase hex characters. */
    val sha256Hex: String,
)

/**
 * Parser for the server's `SHA256SUMS` text (plain Kotlin, JVM-tested).
 *
 * Only the standard two-column form is supported — `<64 hex>` followed by
 * whitespace (the canonical output uses two spaces or " *", the latter is the
 * binary-mode marker) and the file name. Blank lines, `#` comments and
 * anything unparseable (checksums proper, GNU-escaped names, truncations) are
 * skipped rather than rejected: a manifest listing other files must not make
 * the update check fail.
 */
object Sha256Sums {

    /**
     * Parses the whole manifest into entries in file order.
     *
     * @param text the raw response body (any line ending; `\r` is stripped).
     */
    fun parse(text: String): List<Sha256SumsEntry> =
        text.lines().mapNotNull(::parseLine)

    /** The entry for [fileName], or null when the manifest does not list it. */
    fun entryFor(entries: List<Sha256SumsEntry>, fileName: String): Sha256SumsEntry? =
        entries.firstOrNull { it.fileName == fileName }

    /**
     * Parses one line, or null when the line is blank, a comment, or has no
     * well-formed 64-hex checksum + name pair.
     */
    private fun parseLine(line: String): Sha256SumsEntry? {
        val trimmed = line.trim().trimEnd('\r')
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val match = LINE.matchEntire(trimmed) ?: return null
        val hash = match.groupValues[GROUP_HASH].lowercase()
        val name = match.groupValues[GROUP_NAME]
        if (name.isEmpty()) return null
        return Sha256SumsEntry(fileName = name, sha256Hex = hash)
    }

    private val LINE = Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*)$")

    private const val GROUP_HASH = 1

    private const val GROUP_NAME = 2
}
