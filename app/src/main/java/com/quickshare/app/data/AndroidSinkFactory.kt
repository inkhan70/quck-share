package com.quickshare.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.quickshare.core.transfer.FileSink
import com.quickshare.core.transfer.SinkFactory
import com.quickshare.core.transfer.TransferException
import com.quickshare.core.transfer.TransferFileInfo
import com.quickshare.core.util.Logs
import com.quickshare.core.util.MimeTypes
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Allocates receive destinations.
 *
 * Chosen strategy, and why: bytes are streamed **directly into the user's SAF folder**, not into app
 * storage and then copied. That matters twice over - it halves the disk space a receive needs (no
 * second copy), and it halves the I/O, which is the difference between a 4 GB video taking 40 s and
 * 80 s on mid-range hardware. Staging through app storage is kept as a fallback for the case where
 * the SAF provider will not give a writable document.
 */
class AndroidSinkFactory(
    private val context: Context,
    private val registry: SafDocumentRegistry,
) : SinkFactory {

    private companion object {
        const val TAG = "SinkFactory"
    }

    override fun create(info: TransferFileInfo): FileSink {
        val folderUri = registry.ensureTargetFolder()
            ?: throw TransferException(
                "No save folder is available. Open Settings and choose where received files should go.",
                code = "no_destination",
            )

        // Resolve duplicates against the provider's real contents, so "clip.mp4" becomes
        // "clip (1).mp4" rather than overwriting what is already there.
        val uniqueName = registry.uniqueName(folderUri, info.name)
        val mime = info.mime.takeIf { it.isNotBlank() } ?: MimeTypes.fromFileName(uniqueName)

        val documentUri = try {
            registry.createDocument(folderUri, uniqueName, mime)
        } catch (e: Exception) {
            Logs.w(TAG, "SAF create failed for '$uniqueName'; falling back to staging", e)
            return stagingSink(uniqueName, mime)
        }

        return SafFileSink(context, documentUri, uniqueName, mime, registry, folderUri)
    }

    private fun stagingSink(name: String, mime: String): FileSink {
        val stagingDir = File(context.filesDir, AndroidSpaceProvider.STAGING_DIR).apply { mkdirs() }
        val unique = com.quickshare.core.util.SafePaths.uniqueName(
            desired = name,
            exists = { candidate -> File(stagingDir, candidate).exists() },
        )
        return StagingFileSink(context, File(stagingDir, unique), unique, mime, registry)
    }

    /** Writes straight into the SAF document the user granted. */
    private class SafFileSink(
        private val context: Context,
        private val documentUri: Uri,
        override val name: String,
        private val mime: String,
        private val registry: SafDocumentRegistry,
        private val folderUri: Uri,
    ) : FileSink {

        override val uri: String = documentUri.toString()

        override fun openStream(): OutputStream {
            val raw = context.contentResolver.openOutputStream(documentUri, "wt")
                ?: throw TransferException(
                    "The save location refused to open '$name' for writing. Check that the folder still exists.",
                    code = "destination_write_failed",
                )
            return BufferedOutputStream(raw, 64 * 1024)
        }

        override fun delete() {
            try {
                DocumentFile.fromSingleUri(context, documentUri)?.delete()
            } catch (e: Exception) {
                Logs.d(TAG, "Could not delete partial '$name': ${e.message}")
            }
        }

        override fun onSuccess(verified: Boolean) {
            // Only publish to the gallery once the checksum matched: a corrupt image must not appear
            // in the user's photo library.
            // Parenthesised deliberately: `&&` binds tighter than `||`, so the unparenthesised form
            // would scan a *video* into the gallery even when verification had failed.
            // The "Save images to gallery" switch in Settings was never consulted; honour it here.
            val galleryEnabled = com.quickshare.app.core.ServiceLocator.settings().saveImagesToGallery != false
            if (galleryEnabled && verified && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(documentUri.toString()),
                        arrayOf(mime),
                        null,
                    )
                } catch (e: Exception) {
                    Logs.w(TAG, "Media scan failed for '$name'", e)
                }
            }
            Logs.i(TAG, "Saved '$name' to ${folderUri}")
        }
    }

    /**
     * Fallback sink: writes into app-private storage, then copies into the SAF folder on success.
     * Only used when the provider will not hand out a writable document (rare, but real on some
     * OEM file providers and on SD cards formatted by odd tools).
     */
    private class StagingFileSink(
        private val context: Context,
        private val file: File,
        override val name: String,
        private val mime: String,
        private val registry: SafDocumentRegistry,
    ) : FileSink {

        override val uri: String = file.absolutePath

        override fun openStream(): OutputStream =
            BufferedOutputStream(FileOutputStream(file), 64 * 1024)

        override fun delete() {
            if (file.exists() && !file.delete()) {
                Logs.d(TAG, "Could not delete staging file ${file.name}")
            }
        }

        override fun onSuccess(verified: Boolean) {
            if (!verified) {
                // A corrupt copy in private app storage is invisible to the user and just eats space.
                delete()
                return
            }
            val folderUri = registry.ensureTargetFolder() ?: return
            try {
                context.contentResolver.openOutputStream(registry.createDocument(folderUri, name, mime), "wt")
                    ?.use { out -> file.inputStream().use { input -> input.copyTo(out, 64 * 1024) } }
                file.delete()
                Logs.i(TAG, "Copied '$name' from staging into the save folder")
            } catch (e: Exception) {
                Logs.w(TAG, "Could not copy '$name' out of staging; it remains in app storage", e)
            }
        }
    }
}

/**
 * Adds a completed received file to the public Downloads collection.
 *
 * Only used when the user has not chosen a SAF folder, as a last resort so that a receive is never
 * lost. On Android 10+ this goes through MediaStore, which needs no storage permission at all.
 */
object MediaStoreExporter {

    private const val TAG = "MediaStoreExporter"

    fun exportToDownloads(context: Context, file: File, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QuickShare")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(uri, "wt")?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Logs.w(TAG, "Could not export ${file.name} to Downloads", e)
            null
        }
    }
}
