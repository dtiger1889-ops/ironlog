// Top-level build file. Versions are the proven Apps toolchain (AGP 8.5.2 / Kotlin 1.9.25 / Gradle 8.7).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
    // Compose Preview Screenshot Testing (Google). The plugin alphas track AGP versions
    // (alpha11->8.13, alpha12->9.0); alpha03 is the AGP 8.5.x-era build. alpha15 configures
    // but its class-bundling leaves screenshotTestJars empty under AGP 8.5.2 (0 previews found).
    id("com.android.compose.screenshot") version "0.0.1-alpha03" apply false
}
