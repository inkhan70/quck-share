package com.quickshare.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path-traversal and filename-hardening tests.
 *
 * Every case here is a specific attack or a specific real-world filename that has historically
 * broken naive sanitizers. The property asserted throughout is structural: whatever comes out
 * contains no path separator, so it cannot address anything outside the destination directory.
 */
class SafePathsTest {

    @Test
    fun `plain file names are preserved`() {
        assertEquals("holiday.jpg", SafePaths.sanitizeFileName("holiday.jpg"))
        assertEquals("My Report.pdf", SafePaths.sanitizeFileName("My Report.pdf"))
    }

    @Test
    fun `unix traversal is reduced to the base name`() {
        assertEquals("passwd", SafePaths.sanitizeFileName("../../etc/passwd"))
        assertEquals("hosts", SafePaths.sanitizeFileName("/etc/hosts"))
    }

    @Test
    fun `windows traversal is reduced to the base name`() {
        assertEquals("system32", SafePaths.sanitizeFileName("..\\..\\windows\\system32"))
        assertEquals("boot.ini", SafePaths.sanitizeFileName("C:\\boot.ini"))
    }

    @Test
    fun `mixed separators are handled`() {
        assertEquals("passwd", SafePaths.sanitizeFileName("..\\../home/../etc/passwd"))
    }

    @Test
    fun `no sanitized result ever contains a separator`() {
        val hostile = listOf(
            "../../etc/passwd", "..\\..\\x", "a/b/c", "a\\b\\c", "....//....//x",
            "/absolute/path", "\\\\server\\share", "C:/Windows/System32/evil.dll",
        )
        for (raw in hostile) {
            val safe = SafePaths.sanitizeFileName(raw)
            assertFalse("separator survived in '$safe' from '$raw'", safe.contains('/'))
            assertFalse("separator survived in '$safe' from '$raw'", safe.contains('\\'))
        }
    }

    @Test
    fun `a bare dot or dotdot becomes the unnamed fallback`() {
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName("."))
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName(".."))
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName("..."))
    }

    @Test
    fun `null and empty input become the unnamed fallback`() {
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName(null))
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName(""))
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName("   "))
    }

    @Test
    fun `control characters and NUL are stripped`() {
        val safe = SafePaths.sanitizeFileName("evil\u0000name\u0001.txt")
        assertFalse(safe.contains('\u0000'))
        assertFalse(safe.contains('\u0001'))
        assertTrue(safe.endsWith(".txt"))
    }

    @Test
    fun `newlines cannot be smuggled into a name`() {
        val safe = SafePaths.sanitizeFileName("a\nb\r\nc.txt")
        assertFalse(safe.contains('\n'))
        assertFalse(safe.contains('\r'))
    }

    @Test
    fun `windows reserved device names are prefixed`() {
        assertTrue(SafePaths.sanitizeFileName("CON").startsWith("_"))
        assertTrue(SafePaths.sanitizeFileName("nul.txt").startsWith("_"))
        assertTrue(SafePaths.sanitizeFileName("COM1").startsWith("_"))
        assertTrue(SafePaths.sanitizeFileName("LPT9.log").startsWith("_"))
    }

    @Test
    fun `trailing spaces are trimmed and internal dot runs are defused`() {
        assertEquals("report", SafePaths.sanitizeFileName("report   "))

        // A run of dots is neutralised in place rather than stripped, so the result still cannot
        // contain a traversal token. The security property is asserted rather than the exact text.
        val dotted = SafePaths.sanitizeFileName("report...")
        assertFalse("traversal token survived in '$dotted'", dotted.contains(".."))
        assertTrue(dotted.startsWith("report"))

        // A name made *entirely* of dots is always rejected.
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName("..."))
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeFileName("."))
    }

    @Test
    fun `an enormous name is clamped but keeps its extension`() {
        val safe = SafePaths.sanitizeFileName("x".repeat(5000) + ".mp4")
        assertTrue("length was ${safe.length}", safe.length <= 180)
        assertTrue("extension must survive the clamp", safe.endsWith(".mp4"))
    }

    @Test
    fun `relative path sanitization drops traversal segments`() {
        val safe = SafePaths.sanitizeRelativePath("../../etc/passwd")
        assertFalse(safe.contains(".."))
        assertEquals("etc/passwd", safe)
    }

    @Test
    fun `relative path sanitization of nonsense yields the fallback`() {
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeRelativePath("..//.."))
        assertEquals(SafePaths.UNNAMED, SafePaths.sanitizeRelativePath(null))
    }

    @Test
    fun `isInside rejects a sibling directory with a shared prefix`() {
        val root = java.io.File(System.getProperty("java.io.tmpdir"), "qs_root")
        root.mkdirs()
        val inside = java.io.File(root, "a.txt")
        val outside = java.io.File(System.getProperty("java.io.tmpdir"), "qs_root_evil/a.txt")
        assertTrue(SafePaths.isInside(root, inside))
        assertFalse("a prefix-sharing sibling must not count as inside", SafePaths.isInside(root, outside))
    }

    @Test
    fun `uniqueName returns the original when it is free`() {
        assertEquals("clip.mp4", SafePaths.uniqueName("clip.mp4", exists = { false }))
    }

    @Test
    fun `uniqueName numbers duplicates before the extension`() {
        val taken = setOf("clip.mp4", "clip (1).mp4")
        assertEquals("clip (2).mp4", SafePaths.uniqueName("clip.mp4", exists = { it in taken }))
    }

    @Test
    fun `uniqueName handles a file with no extension`() {
        val taken = setOf("README")
        assertEquals("README (1)", SafePaths.uniqueName("README", exists = { it in taken }))
    }

    @Test
    fun `uniqueName sanitizes before checking`() {
        // A hostile name must not be able to skip the collision check by looking different.
        val result = SafePaths.uniqueName("../../clip.mp4", exists = { false })
        assertEquals("clip.mp4", result)
    }

    @Test
    fun `extensionOf handles normal, missing and odd extensions`() {
        assertEquals("mp4", SafePaths.extensionOf("a.mp4"))
        assertEquals("jpg", SafePaths.extensionOf("a.b.c.jpg"))
        assertEquals("", SafePaths.extensionOf("noextension"))
        assertEquals("", SafePaths.extensionOf("trailing."))
        assertEquals("", SafePaths.extensionOf("weird.a b"))
    }
}
