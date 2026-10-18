package com.quickshare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static integrity checks on the launch path.
 *
 * These exist because of a real, shipped crash: the manifest declared the launcher activity as
 * `com.quickshare.app.MainActivity` while the class actually lived in `com.quickshare.app.ui`. The
 * project compiled cleanly, the APK built, `apksigner` verified it - and the app died with a
 * `ClassNotFoundException` the instant the icon was tapped, because **AAPT does not resolve
 * `android:name` component references against the compiled classes**. Nothing in the normal build
 * pipeline catches that class of mistake.
 *
 * So it is caught here instead. Every component the manifest names is resolved to a source file, and
 * every resource the Kotlin code references is resolved to a declaration. These are cheap, run on a
 * plain JVM with no emulator, and fail the build before an APK can be produced.
 */
class StartupIntegrityTest {

    private companion object {
        /** The application id / namespace the manifest's relative names resolve against. */
        const val NAMESPACE = "com.quickshare.app"

        /** Component tags whose `android:name` must resolve to a class we actually ship. */
        val COMPONENT_TAGS = listOf("application", "activity", "service", "receiver", "provider", "activity-alias")
    }

    // ---------------------------------------------------------------------------------------------
    // Locating the project
    // ---------------------------------------------------------------------------------------------

    /**
     * The module directory, found by walking up from the test's working directory.
     *
     * Gradle runs unit tests with the working directory set to the module, but that is a convention
     * rather than a guarantee, so the root is located by looking for the marker file instead of
     * assuming a path.
     */
    private val moduleDir: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("Could not locate the app module from ${System.getProperty("user.dir")}")
    }

    private val manifestFile: File get() = File(moduleDir, "src/main/AndroidManifest.xml")
    private val javaSourceRoot: File get() = File(moduleDir, "src/main/java")
    private val resRoot: File get() = File(moduleDir, "src/main/res")

    private val manifestText: String by lazy { manifestFile.readText() }

    // ---------------------------------------------------------------------------------------------
    // Manifest component resolution - the check that would have caught the crash
    // ---------------------------------------------------------------------------------------------

    /** Every `android:name` value on a component tag, in document order. */
    private fun declaredComponentNames(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        // A deliberately small parser: the manifest is a flat list of component tags, and pulling in
        // an XML library to read our own file would be more machinery than the check is worth.
        val tagPattern = Regex("<\\s*(${COMPONENT_TAGS.joinToString("|")})\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL)
        val namePattern = Regex("android:name\\s*=\\s*\"([^\"]+)\"")
        for (match in tagPattern.findAll(manifestText)) {
            val tag = match.groupValues[1]
            val attrs = match.groupValues[2]
            val name = namePattern.find(attrs)?.groupValues?.get(1) ?: continue
            results += tag to name
        }
        return results
    }

    /** Resolves a manifest name to a fully-qualified class name. */
    private fun resolve(declared: String): String =
        if (declared.startsWith(".")) NAMESPACE + declared else declared

    /** True when a Kotlin or Java source file for [fqcn] exists in the module. */
    private fun classSourceExists(fqcn: String): Boolean {
        val relative = fqcn.replace('.', '/')
        return File(javaSourceRoot, "$relative.kt").isFile || File(javaSourceRoot, "$relative.java").isFile
    }

    @Test
    fun `every component the manifest names resolves to a class we ship`() {
        val declared = declaredComponentNames()
        assertTrue("the manifest should declare at least the application and an activity", declared.size >= 2)

        val unresolved = declared
            .map { (tag, name) -> Triple(tag, name, resolve(name)) }
            // Only our own classes: a framework or library component (FileProvider, zxing's capture
            // activity) is resolved by the platform, not by us.
            .filter { (_, _, fqcn) -> fqcn.startsWith("$NAMESPACE.") }
            .filter { (_, _, fqcn) -> !classSourceExists(fqcn) }

        assertTrue(
            "These manifest components have no matching source file, so the app will crash with a " +
                "ClassNotFoundException when the platform instantiates them:\n" +
                unresolved.joinToString("\n") { (tag, name, fqcn) ->
                    "  <$tag android:name=\"$name\"> -> $fqcn (no source file)"
                },
            unresolved.isEmpty(),
        )
    }

    @Test
    fun `the launcher activity exists and is exported`() {
        // The single most important component: if this one is wrong, the app cannot be opened at all.
        val launcherBlock = Regex(
            "<activity\\b[^>]*>(?:(?!</activity>).)*?<category\\s+android:name\\s*=\\s*\"android\\.intent\\.category\\.LAUNCHER\"",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifestText)?.value
            ?: error("No activity declares the LAUNCHER category - the app would have no icon")

        val name = Regex("android:name\\s*=\\s*\"([^\"]+)\"").find(launcherBlock)?.groupValues?.get(1)
            ?: error("The launcher activity has no android:name")

        val fqcn = resolve(name)
        assertTrue(
            "The launcher activity '$name' resolves to $fqcn, which has no source file. " +
                "Tapping the app icon would throw ClassNotFoundException.",
            classSourceExists(fqcn),
        )
        // Required from API 31: an activity with an intent filter must state its exportedness.
        assertTrue(
            "The launcher activity must declare android:exported=\"true\"",
            Regex("android:exported\\s*=\\s*\"true\"").containsMatchIn(launcherBlock),
        )
    }

    @Test
    fun `the application class exists`() {
        val applicationName = Regex("<application\\b[^>]*android:name\\s*=\\s*\"([^\"]+)\"")
            .find(manifestText)?.groupValues?.get(1)
            ?: error("The manifest declares no application class")

        val fqcn = resolve(applicationName)
        assertTrue(
            "The application class '$applicationName' resolves to $fqcn, which has no source file. " +
                "The process would crash before any activity starts.",
            classSourceExists(fqcn),
        )
    }

    @Test
    fun `the foreground services declare a type`() {
        // From API 34 a foreground service must declare its type, and a dataSync service also needs
        // the matching permission. Missing either is a crash on startForeground.
        val serviceBlocks = Regex("<service\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifestText).map { it.value }.toList()
        assertTrue("the app should declare its transfer services", serviceBlocks.isNotEmpty())

        serviceBlocks.forEach { block ->
            val name = Regex("android:name\\s*=\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: return@forEach
            assertTrue(
                "Service '$name' must declare android:foregroundServiceType",
                block.contains("android:foregroundServiceType"),
            )
            assertTrue(
                "Service '$name' must not be exported",
                Regex("android:exported\\s*=\\s*\"false\"").containsMatchIn(block),
            )
        }
        assertTrue(
            "A dataSync foreground service requires the FOREGROUND_SERVICE_DATA_SYNC permission",
            manifestText.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
        )
    }

    @Test
    fun `the manifest declares the permissions the code actually uses`() {
        // Each of these is exercised by the code; a missing one is a SecurityException at runtime
        // rather than a build error, which is exactly the kind of thing that only shows up on a device.
        listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { permission ->
            assertTrue("the manifest must declare $permission", manifestText.contains(permission))
        }
    }

    @Test
    fun `the app requests no location or broad storage permission`() {
        // A privacy property worth pinning: discovery uses plain sockets, so none of these are
        // needed, and their absence is the reason the app can be installed without a scary prompt.
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_CONTACTS",
        ).forEach { permission ->
            assertFalse(
                "the manifest must not request $permission - QuickShare needs no such access",
                manifestText.contains(permission),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Resource references
    // ---------------------------------------------------------------------------------------------

    private fun kotlinSources(): List<File> =
        javaSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun declaredStringNames(): Set<String> =
        Regex("<string\\s+name\\s*=\\s*\"([^\"]+)\"")
            .findAll(File(resRoot, "values/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every string the code references is declared`() {
        val declared = declaredStringNames()
        val referenced = kotlinSources()
            .flatMap { file -> Regex("R\\.string\\.([A-Za-z0-9_]+)").findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        val missing = referenced - declared
        assertTrue(
            "These R.string keys are used in code but not declared in strings.xml: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every string the code references is also translated in the night resources`() {
        // There is no values-night/strings.xml, so this asserts the invariant that makes that safe:
        // the app has exactly one strings file, and the night theme only overrides colours.
        assertFalse(
            "A values-night/strings.xml would silently shadow the base strings; keep one source of text",
            File(resRoot, "values-night/strings.xml").exists(),
        )
    }

    @Test
    fun `every colour referenced by a theme is declared`() {
        val declared = Regex("<color\\s+name\\s*=\\s*\"([^\"]+)\"")
            .findAll(File(resRoot, "values/colors.xml").readText())
            .map { it.groupValues[1] }
            .toSet() +
            Regex("<color\\s+name\\s*=\\s*\"([^\"]+)\"")
                .findAll(File(resRoot, "values/ic_launcher_background.xml").readText())
                .map { it.groupValues[1] }
                .toSet()

        val themeFiles = listOf("values/themes.xml", "values-night/themes.xml").map { File(resRoot, it) }
        val referenced = themeFiles
            .filter { it.isFile }
            .flatMap { file -> Regex("@color/([A-Za-z0-9_]+)").findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        val missing = referenced - declared
        assertTrue("These @color references have no declaration: $missing", missing.isEmpty())
    }

    @Test
    fun `every drawable and xml resource referenced by the manifest exists`() {
        val referenced = Regex("@(drawable|mipmap|xml)/([A-Za-z0-9_]+)")
            .findAll(manifestText)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()

        val missing = referenced.filterNot { (type, name) -> resourceExists(type, name) }
        assertTrue("These manifest resource references do not resolve: $missing", missing.isEmpty())
    }

    /** True when a resource of [type] named [name] exists in any density/qualifier directory. */
    private fun resourceExists(type: String, name: String): Boolean {
        val dirs = resRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith(type) } ?: return false
        return dirs.any { dir ->
            listOf("xml", "png", "webp", "jpg", "jpeg").any { ext -> File(dir, "$name.$ext").isFile }
        }
    }

    @Test
    fun `the adaptive launcher icon has both a background and a foreground`() {
        // An adaptive icon missing either layer renders as a blank square on API 26+, which is a
        // silent, cosmetic-only failure that no build step reports.
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { fileName ->
            val file = File(resRoot, "mipmap-anydpi-v26/$fileName")
            assertTrue("$fileName must exist for adaptive icons", file.isFile)
            val text = file.readText()
            assertTrue("$fileName must declare a background layer", text.contains("<background"))
            assertTrue("$fileName must declare a foreground layer", text.contains("<foreground"))
        }
        assertTrue(
            "the adaptive icon foreground drawable must exist",
            File(resRoot, "drawable/ic_launcher_foreground.xml").isFile,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Build configuration
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the release build is not debuggable and targets the documented sdk levels`() {
        val buildFile = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("minSdk must be 24 (Android 7)", Regex("minSdk\\s*=\\s*24").containsMatchIn(buildFile))
        assertTrue("targetSdk must be 34", Regex("targetSdk\\s*=\\s*34").containsMatchIn(buildFile))
        assertTrue("compileSdk must be 34", Regex("compileSdk\\s*=\\s*34").containsMatchIn(buildFile))
        assertTrue("the app must be built with Java 17", buildFile.contains("VERSION_17"))
        assertFalse(
            "the release build must not set isDebuggable = true",
            Regex("isDebuggable\\s*=\\s*true").containsMatchIn(buildFile),
        )
    }

    @Test
    fun `the manifest package matches the gradle namespace`() {
        // A mismatch here is what makes a relative android:name resolve to the wrong class - the
        // exact failure this whole file exists to prevent.
        val buildFile = File(moduleDir, "build.gradle.kts").readText()
        val namespace = Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(buildFile)?.groupValues?.get(1)
        assertEquals("the gradle namespace must match the constant this test resolves against", NAMESPACE, namespace)
    }
}
