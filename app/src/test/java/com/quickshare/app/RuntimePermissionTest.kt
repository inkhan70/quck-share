package com.quickshare.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks that permissions the platform only grants on request are *actually requested*.
 *
 * This exists because of a real defect: `POST_NOTIFICATIONS` was declared in the manifest but no code
 * ever asked for it. On Android 13+ that means the permission is denied by default, and every
 * transfer notification is silently dropped - the app looks like it is ignoring its own settings, and
 * a transfer waiting for an answer is invisible from outside the app.
 *
 * A declared-but-unrequested permission is invisible to the compiler, to AAPT and to `apksigner`,
 * exactly like the `android:name` mismatch that caused the launch crash. So it is asserted here,
 * where it costs nothing and needs no device.
 */
class RuntimePermissionTest {

    private companion object {
        /** Permissions that Android denies by default unless the app explicitly asks at runtime. */
        val RUNTIME_PERMISSIONS = listOf(
            "android.permission.POST_NOTIFICATIONS",
        )

        /** The ways an app can ask. Any one of these next to the permission name counts as a request. */
        val REQUEST_MECHANISMS = listOf(
            "RequestPermission()",
            "RequestMultiplePermissions()",
            "requestPermissions(",
        )
    }

    private val moduleDir: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("Could not locate the app module from " + System.getProperty("user.dir"))
    }

    private val manifestText: String by lazy {
        File(moduleDir, "src/main/AndroidManifest.xml").readText()
    }

    private val sourceFiles: List<File> by lazy {
        File(moduleDir, "src/main/java")
            .walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .toList()
    }

    @Test
    fun `every runtime permission the manifest declares is actually requested in code`() {
        val missing = mutableListOf<String>()

        for (permission in RUNTIME_PERMISSIONS) {
            // Only permissions this app actually declares are this app's problem.
            if (!manifestText.contains(permission)) continue

            val leaf = permission.substringAfterLast('.')
            val requested = sourceFiles.any { file ->
                val text = file.readText()
                text.contains(leaf) && REQUEST_MECHANISMS.any { text.contains(it) }
            }
            if (!requested) missing += permission
        }

        assertTrue(
            "Declared in the manifest but never requested at runtime, so the platform denies it " +
                "silently: " + missing,
            missing.isEmpty(),
        )
    }
}
