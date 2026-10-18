package com.quickshare.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.quickshare.app.core.ServiceLocator
import com.quickshare.core.transfer.FileSource
import com.quickshare.core.util.Logs
import com.quickshare.core.util.MimeTypes
import com.quickshare.core.util.SafePaths
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A file the user picked, described without ever reading its bytes.
 *
 * Metadata is resolved once at pick time (name, size, MIME) and cached, because the sender needs the
 * real size before the offer is built, and querying a content URI is a relatively expensive
 * provider round trip that must not happen inside the transfer loop.
 */
class DocumentFileSource(
    private val context: Context,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mime: String,
    override val relativePath: String = name,
) : FileSource {

    override fun openStream(): InputStream = try {
        // A fresh stream every time, so a retry re-reads from the beginning rather than resuming
        // wherever the previous attempt failed.
        context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Could not open '$name'. The file may have been moved or deleted.")
    } catch (e: SecurityException) {
        throw java.io.IOException("QuickShare no longer has access to '$name'. Pick it again.", e)
    }

    companion object {
        private const val TAG = "DocumentFileSource"

        /**
         * Resolves a picked URI into a source.
         *
         * Falls back gracefully: a provider that will not report a display name still yields a usable
         * (if generically named) source rather than dropping the user's selection. A file whose size
         * cannot be determined at all is dropped (null) rather than being offered as empty.
         */
        fun from(context: Context, uri: Uri): DocumentFileSource? {
            return try {
                var displayName: String? = null
                var reportedSize = -1L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedSize = cursor.getLong(sizeIndex)
                    }
                }

                val safeName = SafePaths.sanitizeFileName(displayName ?: uri.lastPathSegment ?: "shared_file")
                val resolvedMime = context.contentResolver.getType(uri)
                    ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: MimeTypes.fromFileName(safeName)

                val size = if (reportedSize >= 0) {
                    reportedSize
                } else {
                    // Some providers refuse to report SIZE. Count the bytes once rather than sending a
                    // wrong figure in the manifest, which would abort the transfer on mismatch.
                    countBytes(context, uri)
                }
                if (size < 0) {
                    // Could not read it at all. Describing it as an empty file would offer (and
                    // "successfully" send) a zero-byte copy of the user's data.
                    Logs.w(TAG, "Could not determine the size of $uri; leaving it out of the selection")
                    return null
                }

                DocumentFileSource(
                    context = context,
                    uri = uri,
                    name = safeName,
                    size = size,
                    mime = resolvedMime,
                )
            } catch (e: Exception) {
                Logs.w(TAG, "Could not describe $uri", e)
                null
            }
        }

        private fun countBytes(context: Context, uri: Uri): Long = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                }
                total
            } ?: -1L
        } catch (e: Exception) {
            Logs.w(TAG, "Could not determine the size of $uri", e)
            -1L
        }
    }
}

/** A plain filesystem file, used for the staging copies and for the demo/test path. */
class PlainFileSource(
    private val file: File,
    override val relativePath: String = file.name,
) : FileSource {

    override val name: String = SafePaths.sanitizeFileName(file.name)
    override val size: Long = file.length()
    override val mime: String = MimeTypes.fromFileName(name)

    override fun openStream(): InputStream = FileInputStream(file)
}
