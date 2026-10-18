import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Release signing.
 *
 * keystore.properties (root of the repo) is intentionally NOT committed and NOT shipped in the
 * source archive. When it is absent -- e.g. a fresh clone, or the GitHub Actions runner without
 * secrets configured -- the release build falls back to the debug signing config so that
 * `./gradlew assembleRelease` always yields an installable APK instead of failing.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
    }
}

android {
    namespace = "com.quickshare.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quickshare.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += setOf("en")
        vectorDrawables.useSupportLibrary = true

        // The wire protocol version this build speaks. Sent during the handshake so that a
        // future protocol bump can be detected instead of silently mis-parsing frames.
        buildConfigField("int", "PROTOCOL_VERSION", "1")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        } else {
            logger.lifecycle(
                "QuickShare: keystore.properties not found - the release build will be signed " +
                    "with the debug key. Create keystore.properties to sign with your own key."
            )
        }
    }

    buildTypes {
        getByName("release") {
            // Minification and resource shrinking are OFF, deliberately.
            //
            // R8 is by far the largest single allocation in the build - it loads the whole program
            // graph plus the Android platform's library metadata - and on a 2 GB build container it is
            // killed before it finishes, which yields no APK at all. Shipping a slightly larger APK is
            // strictly better than shipping nothing.
            //
            // Nothing here depends on obfuscation: there are no secrets in the binary, no
            // reflection-by-name over app classes, and Compose resolves everything at compile time. If
            // you build in an environment with more memory, flipping these two flags back on is safe
            // and will cut roughly a third off the download size.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Screens are written with Compose. The XML resources that remain are the theme, strings and
        // dimens, so the app and its Compose tree share one set of colour and text sources.
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Pinned to the version that matches Kotlin 1.9.24 exactly; a mismatch here is the single
        // most common cause of a Compose build failure.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint findings are reported by `./gradlew :app:lintRelease`; they never fail the build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // All shared logic lives here.
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")

    // Compose. The BOM keeps every Compose artifact on one mutually compatible version.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // QR pairing. zxing-android-embedded supplies the capture activity and the scanning UI; zxing
    // core is declared explicitly as well, because QrCode.kt imports its encoder classes directly
    // and relying on a transitive artifact for a direct import is how a routine dependency bump
    // turns into a compile error.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
