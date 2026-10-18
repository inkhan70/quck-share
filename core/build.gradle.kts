plugins {
    id("org.jetbrains.kotlin.jvm")
}

// An explicit target instead of jvmToolchain(17): the toolchain form needs a JDK 17 to be *installed*
// (there is no auto-download plugin configured), so the build failed on machines that only have a newer
// JDK such as 21. Targeting 17 bytecode works on any JDK 17 or newer, and matches the app module.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Gson is the only dependency of the core: it keeps the wire codec declarative.
    implementation("com.google.code.gson:gson:2.10.1")
    // api (not implementation): the public API exposes Flow / StateFlow types to the app module.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "512m"
}
