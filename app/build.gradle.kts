plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    id("com.android.compose.screenshot")
}

android {
    namespace = "dev.ironlog.app"
    compileSdk = 35

    // PC verification rig: render @Preview composables to PNG on the JVM (no emulator).
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "dev.ironlog.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }
    composeOptions {
        // Compose compiler extension matched to Kotlin 1.9.25.
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    // Unit tests read the bundled CSV asset by relative path; keep the working dir at the module root.
    testOptions {
        unitTests.all {
            it.workingDir = projectDir
        }
    }

    // Room migration tests need schemas in the test APK's assets folder.
    sourceSets {
        getByName("androidTest").assets.srcDirs(
            files("$projectDir/schemas")
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // M6: Health Connect — requires compileSdk 36 + AGP 8.9.1+. Toolchain is AGP 8.5.2/sdk 35.
    // Real HC gateway is stubbed until the toolchain is upgraded; no-op gateway is active in prod.
    // Uncomment when toolchain allows: implementation("androidx.health.connect:connect-client:1.1.0-rc01")

    // M6: WorkManager for periodic auto-backup
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // F9: DocumentFile.fromTreeUri -- the picked auto-backup folder is a SAF tree uri.
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("com.patrykandpatrick.vico:compose-m3:1.14.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Compose Preview Screenshot Testing (PC verification rig).
    // NOTE: do NOT add com.android.tools.screenshot:screenshot-validation-api here — it drags in
    // kotlin-stdlib 2.2 (metadata too new for the Kotlin 1.9.25 compiler) and breaks the build.
    // Plain @Preview discovery (methods inside a class) needs only ui-tooling.
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
