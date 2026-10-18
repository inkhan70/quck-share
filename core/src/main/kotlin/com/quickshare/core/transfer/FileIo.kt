package com.quickshare.core.transfer

import java.io.InputStream
import java.io.OutputStream

/**
 * A file to be sent.
 *
 * The core never sees a `Uri` or a `File`: it only ever calls [openStream], which is how the same
 * engine works for a SAF document, a plain file, or an in-memory test fixture. Implementations must
 * return a *fresh* stream on every call, because a retry re-reads from the start.
 */
interface FileSource {
    val name: String
    val relativePath: String
    val size: Long
    val mime: String

    /** Opens the content for reading. The caller closes it. */
    fun openStream(): InputStream

    fun toInfo(): TransferFileInfo = TransferFileInfo(
        name = name,
        relativePath = relativePath,
        size = size,
        mime = mime,
    )
}

/**
 * A destination for received bytes.
 *
 * @param name the final, sanitized filename.
 * @param uri opaque handle the platform layer understands (a SAF document URI on Android).
 */
interface FileSink {
    val name: String
    val uri: String

    /** Opens the destination for writing. The caller closes it. */
    fun openStream(): OutputStream

    /**
     * Removes a partially written file. Called on cancel/failure so a half-file is never left
     * behind looking like a complete download.
     */
    fun delete()

    /**
     * Called after a successful, checksum-verified write. Implementations may publish the file
     * (e.g. move it out of a staging directory) or scan it into the media store.
     */
    fun onSuccess(verified: Boolean) {}
}

/**
 * Creates sinks for an incoming offer.
 *
 * The platform layer implements this over the Storage Access Framework, which is what keeps every
 * write inside a directory the user explicitly granted. It is also where duplicate filenames are
 * resolved (`clip.mp4` -> `clip (1).mp4`).
 */
interface SinkFactory {
    /**
     * Allocates a sink for [info].
     *
     * @throws TransferException when the destination is missing, read-only, or out of space.
     */
    fun create(info: TransferFileInfo): FileSink
}

/**
 * Free-space query, kept behind an interface because the Android implementation needs a `StatFs`
 * on a content URI, which cannot be constructed in a JVM test.
 */
interface SpaceProvider {
    /** Usable bytes at the destination, or -1 when unknown. */
    fun usableBytes(destination: String?): Long
}
