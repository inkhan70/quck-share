package com.quickshare.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import com.quickshare.core.transfer.SpaceProvider
import com.quickshare.core.util.Logs
import java.io.File

/**
 * Reports free space at the receive destination.
 *
 * Getting this right matters more than it looks: the check happens *before* the user is asked to
 * accept, so an impossible transfer is refused with a clear explanation instead of failing an hour
 * later at 90%.
 *
 * Two locations are consulted, and the smaller of the two wins, because a receive needs room in
 * both:
 *  * the app's private staging area, where bytes land first, and
 *  * the user's SAF folder, where the file is copied once verified.
 */
class AndroidSpaceProvider(context: Context) : SpaceProvider {

    private val appContext = context.applicationContext

    override fun usableBytes(destination: String?): Long {
        val stagingFree = freeSpaceAt(File(appContext.filesDir, STAGING_DIR))
        val safFree = destination?.let { freeSpaceAtTree(Uri.parse(it)) } ?: -1L

        return when {
            stagingFree < 0 && safFree < 0 -> -1L // Unknown: the caller will not block on a guess.
            stagingFree < 0 -> safFree
            safFree < 0 -> stagingFree
            else -> minOf(stagingFree, safFree)
        }
    }

    /** Free bytes on the filesystem holding [path], or -1 when it cannot be determined. */
    fun freeSpaceAt(path: File): Long = try {
        if (!path.exists()) path.mkdirs()
        val stat = StatFs(path.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        Logs.w(TAG, "Could not read free space for ${path.absolutePath}", e)
        -1L
    }

    /**
     * Free bytes for a SAF tree, or -1 when it cannot be determined.
     *
     * Derived from the tree's document id, which the SAF contract exposes publicly as
     * `volume:relative/path` (e.g. `primary:Download`). Only the volume - the public
     * `DocumentsContract.getTreeDocumentId` result - is used, and it is mapped to its conventional
     * mount point.
     *
     * Note what this deliberately does NOT do: `DocumentsContract.findDocumentPath` would give the
     * exact path, but it is a hidden API (absent from the public SDK), so depending on it would mean
     * an unverifiable reflection call on every device. An unrecognised volume therefore answers
     * "unknown" (-1), which makes the caller skip the space check rather than refuse a transfer on a
     * bad guess.
     */
    private fun freeSpaceAtTree(uri: Uri): Long {
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return -1L
        }
        val volume = documentId.substringBefore(':')
        val root = when {
            volume.isEmpty() || volume == "primary" -> Environment.getExternalStorageDirectory()
            else -> File("/storage/$volume")
        }
        if (!root.exists()) {
            Logs.d(TAG, "Save location '$documentId' is on an unknown volume; free space unknown")
            return -1L
        }
        return freeSpaceAt(root)
    }

    companion object {
        private const val TAG = "SpaceProvider"
        const val STAGING_DIR = "staging"
    }
}
