// Root build.gradle.kts — PeerLink
//
// Modern 2026 toolchain (the foundation everything else builds on):
//   Kotlin 2.3.21  ←→  Compose Compiler Gradle plugin 2.3.21  (always version-matched)
//   AGP 8.9.1      ←→  Gradle 8.11.1   (confirmed-good pairing for compileSdk 36)
//   Compose BOM 2026.05.00  →  Compose 1.11 + Material 3 Expressive
//
// NOTE: the old `kotlinCompilerExtensionVersion` is gone — since Kotlin 2.0 the Compose
// compiler ships as a Gradle plugin (org.jetbrains.kotlin.plugin.compose) applied per-module.
//
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library")     version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}
