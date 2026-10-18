package com.quickshare.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the About / How-to-use content.
 *
 * Like [StartupIntegrityTest], these are static checks on source and resources, so they run on a plain
 * JVM. They pin the things that are easy to break silently: the credit line, and the screens being
 * reachable from the UI at all.
 */
class AboutAndHelpTest {

    private val moduleDir: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("Could not locate the app module from " + System.getProperty("user.dir"))
    }

    private val strings: String by lazy { File(moduleDir, "src/main/res/values/strings.xml").readText() }

    private fun source(relative: String) = File(moduleDir, "src/main/java/com/quickshare/app/$relative").readText()

    private fun stringValue(name: String): String =
        Regex("<string\\s+name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1) ?: error("string '$name' is not declared")

    @Test
    fun `about us credits Cluster_Lab`() {
        assertTrue(stringValue("aboutus_created_by").contains("Cluster_Lab"))
        assertTrue(stringValue("aboutus_created_by").startsWith("Created by"))
        assertTrue(stringValue("aboutus_title").isNotBlank())
    }

    @Test
    fun `the about screen actually shows the credit`() {
        val screen = source("ui/screens/InfoScreens.kt")
        assertTrue(screen.contains("R.string.aboutus_created_by"))
        assertTrue(screen.contains("R.string.aboutus_title"))
    }

    @Test
    fun `the guide covers sending, receiving and troubleshooting`() {
        listOf("howto_before_title", "howto_send_title", "howto_receive_title", "howto_missing_title").forEach {
            assertTrue("$it must have text", stringValue(it).isNotBlank())
        }
        val screen = source("ui/screens/InfoScreens.kt")
        listOf("howto_send_1", "howto_receive_1", "howto_missing_1").forEach {
            assertTrue("$it must be shown", screen.contains("R.string.$it"))
        }
    }

    @Test
    fun `help and about are reachable from the app`() {
        val settings = source("ui/screens/SettingsScreen.kt")
        assertTrue(settings.contains("InfoPage.ABOUT"))
        assertTrue(settings.contains("InfoPage.HOW_TO_USE"))
        assertTrue(source("ui/screens/NearbyScreen.kt").contains("InfoPage.HOW_TO_USE"))
        assertTrue(source("ui/AppShell.kt").contains("InfoScreen("))
    }
}
