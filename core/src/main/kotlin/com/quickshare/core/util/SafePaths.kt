package com.quickshare.core.util

/**
 * Filename and path hardening.
 *
 * Every filename that reaches this app comes from an untrusted peer, so it is treated as hostile
 * input: the sanitizer strips directory components, traversal sequences, control characters,
 * characters that are illegal on common filesystems, and Windows reserved device names. The
 * resulting name is always a single path segment with no separators, which makes path traversal
 * structurally impossible rather than merely filtered.
 */
object SafePaths {

    const val UNNAMED = "unnamed"
    private const val MAX_NAME_LENGTH = 180
    private const val RESERVED_PREFIX = "_"

    /** Characters that are illegal on Windows and confusing on POSIX. */
    private val ILLEGAL_CHARS = charArrayOf('\u0000', '/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t')

    /** DOS device names that cannot be used as file names on Windows. */
    private val RESERVED_NAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /**
     * Reduces an arbitrary peer-supplied path to a single safe filename.
     *
     * @return a non-empty name containing no path separators and no traversal sequences.
     */
    fun sanitizeFileName(raw: String?): String {
        if (raw.isNullOrEmpty()) return UNNAMED

        // 1. Drop every directory component. Both separators are handled, and the split is done on
        //    the raw string so that "..\\..\/etc/passwd" yields "passwd".
        val lastForward = raw.lastIndexOf('/')
        val lastBackward = raw.lastIndexOf('\\')
        val base = when {
            lastForward < 0 && lastBackward < 0 -> raw
            else -> raw.substring(maxOf(lastForward, lastBackward) + 1)
        }

        // 2. Remove illegal and control characters.
        val cleaned = StringBuilder(base.length)
        for (ch in base) {
            when {
                ch in ILLEGAL_CHARS -> cleaned.append('_')
                ch.code < 0x20 || ch.code == 0x7F -> cleaned.append('_')
                else -> cleaned.append(ch)
            }
        }
        var name = cleaned.toString().trim()

        // 3. Neutralise traversal tokens that survived as a whole name.
        if (name == "." || name == ".." || name.all { it == '.' }) name = UNNAMED
        name = name.replace("..", "_")

        // 4. Windows trims trailing dots, so a name that consists only of them would become empty;
        //    that case is folded into UNNAMED. A trailing run like "report..." is left alone, because
        //    stripping it would change a legitimate (if unusual) file name on Linux.
        name = name.trimEnd(' ')
        if (name.isEmpty() || name.all { it == '.' }) return UNNAMED

        // 5. Windows reserved device names. Only the stem matters: "CON.txt" is also reserved.
        val stem = name.substringBefore('.').lowercase()
        if (stem in RESERVED_NAMES) name = RESERVED_PREFIX + name

        // 6. Clamp the length, preserving a *short* extension so the MIME guess still works.
        //    The test is on the extension's own length, not on where the dot sits: a name that is
        //    5000 characters followed by ".mp4" still has a meaningful 4-character extension.
        if (name.length > MAX_NAME_LENGTH) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0) name.substring(dot) else ""
            val extIsUsable = ext.length in 2..12 && ext.any { it != '.' }
            name = if (extIsUsable) {
                name.substring(0, MAX_NAME_LENGTH - ext.length) + ext
            } else {
                name.substring(0, MAX_NAME_LENGTH)
            }
        }

        return name.ifEmpty { UNNAMED }
    }

    /**
     * Sanitizes a peer-supplied relative sub-path (e.g. the folder layout of a shared directory)
     * into a flat, safe path made only of sanitized segments.
     *
     * Any `..` segment is dropped outright, so the result can never escape the destination root.
     */
    fun sanitizeRelativePath(raw: String?): String {
        if (raw.isNullOrEmpty()) return UNNAMED
        val segments = raw.split('/', '\\')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitizeFileName(it) }
            .toList()
        return if (segments.isEmpty()) UNNAMED else segments.joinToString("/")
    }

    /**
     * Verifies that [resolved] really sits inside [root]. Used as a belt-and-braces check for the
     * app-private staging directory, where a plain `java.io.File` (rather than a SAF document)
     * is written.
     */
    fun isInside(root: java.io.File, resolved: java.io.File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = resolved.canonicalFile.toPath()
        return targetPath.startsWith(rootPath)
    }

    /**
     * Produces a non-colliding name by inserting a counter before the extension:
     * `clip.mp4` -> `clip (1).mp4`.
     *
     * @param exists predicate deciding whether a candidate name is already taken.
     */
    fun uniqueName(desired: String, exists: (String) -> Boolean, maxAttempts: Int = 9999): String {
        val safe = sanitizeFileName(desired)
        if (!exists(safe)) return safe

        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""

        for (i in 1..maxAttempts) {
            val candidate = "$stem ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        // Pathological case: fall back to a time-based suffix so a transfer never silently fails.
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    /** Best-effort extension extraction, used for the MIME guess and the SAF file type hint. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1)
        return if (ext.length > 12 || ext.any { !it.isLetterOrDigit() }) "" else ext.lowercase()
    }
}
