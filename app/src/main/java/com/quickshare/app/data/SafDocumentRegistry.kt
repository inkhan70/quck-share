package com.quickshare.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.quickshare.core.util.Logs

/**
 * Remembers the folder the user granted through the Storage Access Framework.
 *
 * Everything QuickShare writes goes through the tree URI stored here, which is what keeps the app
 * inside the one directory the user explicitly picked. The "persistable" permission flag is taken so
 * the grant survives a reboot; without it the folder would silently stop being writable and receives
 * would fail after a restart.
 */
class SafDocumentRegistry(context: Context) {

    companion object {
        private const val TAG = "SafDocumentRegistry"
        private const val PREFS = "quickshare_saf"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_LABEL = "tree_label"

        // Child of Downloads, which every device has and which Play policy allows apps to request.
        const val DEFAULT_CHILD = "QuickShare"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted tree URI, or null when the user has not chosen a folder yet. */
    fun treeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let { raw ->
        try {
            Uri.parse(raw)
        } catch (e: Exception) {
            Logs.w(TAG, "Could not parse the stored tree uri", e)
            null
        }
    }

    fun label(): String? = prefs.getString(KEY_LABEL, null)

    /**
     * Validates and stores a newly granted tree URI.
     *
     * @return the display label on success, or null when the URI is not a usable document tree
     *         (for example the user picked a provider that does not support writing).
     */
    fun setTree(uri: Uri): String? {
        val name = displayNameOf(uri) ?: run {
            Logs.w(TAG, "Rejected tree uri $uri: no display name")
            return null
        }
        return try {
            // Taking the persistable flag is what makes the grant survive a reboot.
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Some providers grant only a one-shot permission; the folder is still usable now, so the
            // grant is stored and only the post-reboot case degrades.
            Logs.w(TAG, "Could not persist the uri permission", e)
        }.let {
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).putString(KEY_LABEL, name).apply()
            Logs.i(TAG, "Save location set to '$name'")
            name
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_TREE_URI).remove(KEY_LABEL).apply()
    }

    /** True when a folder has been chosen and is still reachable and writable. */
    fun isUsable(): Boolean {
        val uri = treeUri() ?: return false
        return try {
            val dir = DocumentFile.fromTreeUri(appContext, uri)
            dir != null && dir.exists() && dir.canWrite()
        } catch (e: Exception) {
            Logs.w(TAG, "Save location is not usable", e)
            false
        }
    }

    /**
     * Creates (or reuses) the QuickShare subfolder inside the granted tree.
     *
     * Keeping files in their own subfolder means a received file never mixes with whatever else the
     * user has in the folder they picked, and deleting a transfer is unambiguous.
     */
    fun ensureTargetFolder(): Uri? {
        val tree = treeUri() ?: return null
        return try {
            val root = DocumentFile.fromTreeUri(appContext, tree) ?: return null
            if (!root.canWrite()) return null

            val existing = root.findFile(DEFAULT_CHILD)
            if (existing != null) {
                if (existing.isDirectory) return existing.uri
                // A file with that name is in the way; write to the root rather than failing.
                Logs.w(TAG, "'$DEFAULT_CHILD' exists and is not a folder; writing to the root")
                return root.uri
            }
            root.createDirectory(DEFAULT_CHILD)?.uri ?: root.uri
        } catch (e: Exception) {
            Logs.w(TAG, "Could not prepare the target folder", e)
            null
        }
    }

    /**
     * Resolves a unique document name inside [folderUri].
     *
     * The registry delegates uniqueness to [DocumentFile.findFile] rather than tracking names
     * itself, because only the provider knows what is really on disk. The numbering scheme comes from
     * [com.quickshare.core.util.SafePaths.uniqueName], so the two agree on the resulting format.
     */
    fun uniqueName(folderUri: Uri, desiredName: String): String {
        return try {
            val folder = DocumentFile.fromTreeUri(appContext, folderUri) ?: return desiredName
            com.quickshare.core.util.SafePaths.uniqueName(
                desired = desiredName,
                exists = { candidate -> folder.findFile(candidate) != null },
            )
        } catch (e: Exception) {
            Logs.w(TAG, "Could not resolve a unique name; using '$desiredName'", e)
            desiredName
        }
    }

    /**
     * Creates a document and returns a writable URI.
     *
     * @throws java.io.IOException when the provider refuses to create the file.
     */
    fun createDocument(folderUri: Uri, name: String, mime: String): Uri {
        val folder = DocumentFile.fromTreeUri(appContext, folderUri)
            ?: throw java.io.IOException("The chosen folder is no longer available. Pick it again in Settings.")
        if (!folder.canWrite()) {
            throw java.io.IOException("QuickShare does not have write access to the chosen folder. Pick it again in Settings.")
        }
        val created = folder.createFile(mime, name)
            ?: throw java.io.IOException("The save location refused to create '$name'. It may be full or read-only.")
        return created.uri
    }

    private fun displayNameOf(uri: Uri): String? = try {
        DocumentFile.fromTreeUri(appContext, uri)?.name
            ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { null }
    } catch (e: Exception) {
        Logs.w(TAG, "Could not read the display name of $uri", e)
        null
    }
}
