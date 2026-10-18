package com.quickshare.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MIME resolution tests: the wire manifest, the SAF hint and the UI icon all depend on this. */
class MimeTypesTest {

    @Test
    fun `common extensions resolve to their real mime types`() {
        assertEquals("image/jpeg", MimeTypes.fromFileName("photo.jpg"))
        assertEquals("video/mp4", MimeTypes.fromFileName("clip.mp4"))
        assertEquals("application/pdf", MimeTypes.fromFileName("doc.pdf"))
        assertEquals("application/zip", MimeTypes.fromFileName("bundle.zip"))
        assertEquals("application/vnd.android.package-archive", MimeTypes.fromFileName("app.apk"))
    }

    @Test
    fun `uppercase extensions are normalized`() {
        assertEquals("image/jpeg", MimeTypes.fromFileName("PHOTO.JPG"))
        assertEquals("application/zip", MimeTypes.fromFileName("Archive.ZIP"))
    }

    @Test
    fun `an unknown or absent extension falls back to octet stream`() {
        assertEquals(MimeTypes.DEFAULT, MimeTypes.fromFileName("mystery"))
        assertEquals(MimeTypes.DEFAULT, MimeTypes.fromFileName("file.unknownext"))
        assertEquals(MimeTypes.DEFAULT, MimeTypes.fromFileName(null))
        assertEquals(MimeTypes.DEFAULT, MimeTypes.fromFileName(""))
    }

    @Test
    fun `categories are assigned for the file types the app advertises`() {
        assertEquals(FileCategory.IMAGE, MimeTypes.categoryOf(null, "a.png"))
        assertEquals(FileCategory.VIDEO, MimeTypes.categoryOf(null, "a.mkv"))
        assertEquals(FileCategory.AUDIO, MimeTypes.categoryOf(null, "a.mp3"))
        assertEquals(FileCategory.PDF, MimeTypes.categoryOf(null, "a.pdf"))
        assertEquals(FileCategory.ARCHIVE, MimeTypes.categoryOf(null, "a.7z"))
        assertEquals(FileCategory.APK, MimeTypes.categoryOf(null, "a.apk"))
        assertEquals(FileCategory.DOCUMENT, MimeTypes.categoryOf(null, "a.docx"))
        assertEquals(FileCategory.OTHER, MimeTypes.categoryOf(null, "a.bin"))
    }

    @Test
    fun `an explicit mime type wins over the extension`() {
        // A provider that reports the real type must be believed over a misleading extension.
        assertEquals(FileCategory.IMAGE, MimeTypes.categoryOf("image/png", "misleading.bin"))
    }

    @Test
    fun `an empty or default mime falls back to the extension`() {
        assertEquals(FileCategory.VIDEO, MimeTypes.categoryOf("", "a.mp4"))
        assertEquals(FileCategory.VIDEO, MimeTypes.categoryOf(MimeTypes.DEFAULT, "a.mp4"))
    }

    @Test
    fun `every advertised send category has a mapping`() {
        // The task lists these explicitly as required send types.
        for ((name, expected) in listOf(
            "photo.jpg" to FileCategory.IMAGE,
            "clip.mp4" to FileCategory.VIDEO,
            "report.pdf" to FileCategory.PDF,
            "app.apk" to FileCategory.APK,
            "archive.zip" to FileCategory.ARCHIVE,
            "notes.docx" to FileCategory.DOCUMENT,
        )) {
            assertEquals("wrong category for $name", expected, MimeTypes.categoryOf(null, name))
        }
    }

    @Test
    fun `a path-like name still resolves its extension`() {
        // Sanitization happens first in the real flow, but the MIME helper must not be fragile.
        assertEquals("image/jpeg", MimeTypes.fromFileName("some/path/photo.jpg"))
    }

    @Test
    fun `assertions about sanitized names hold`() {
        val safe = SafePaths.sanitizeFileName("../../evil.apk")
        assertEquals("evil.apk", safe)
        assertEquals("application/vnd.android.package-archive", MimeTypes.fromFileName(safe))
    }
}
