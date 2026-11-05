plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.meridian.tcmsession"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.meridian.tcmsession"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // The server address a fresh install starts with (AppConfig.serverHost;
        // the login screen's field stays editable). Set defaultServerUrl in
        // gradle.properties to the Cloud Run URL (docs/cloud-deploy.md); blank
        // means the practitioner types a LAN address.
        val defaultServerUrl = (project.findProperty("defaultServerUrl") as String?).orEmpty().trim()
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
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
        viewBinding = true
        compose = true
        buildConfig = true
    }
    // JVM unit tests (src/test): android.util.Log calls return defaults
    // instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Fixtures shared with the server's tests (repo-root testdata/).
        unitTests.all {
            val testdata = rootProject.file("../../testdata")
            it.systemProperty("testdata.dir", testdata.absolutePath)
            it.inputs.dir(testdata)
        }
    }
    // libmuse_android.jar ships its own native .so libs, referenced via
    // src/main/jniLibs — no packagingOptions needed beyond the default.
}

dependencies {
    // Compose. Pinned via the BOM; see the compileSdk note in gradle.properties.
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(files("libs/libmuse_android.jar"))

    // QR pairing: scan the server address off the /pair page instead of typing it.
    // Pinned to 1.4.2, not the 1.3.x line: camera-core's bundled
    // libimage_processing_util_jni.so was only 4KB-page-aligned through 1.3.x
    // (fixed in 1.4.0) and would fail the 16KB-page-size check otherwise.
    // (1.5.x has the fix too but requires bumping compileSdk to 35+.)
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // "Continue with Google" (LoginActivity.signInWithGoogle) — Credential
    // Manager is the current (non-deprecated) API for Sign in with Google;
    // the googleid artifact parses the returned credential into a GoogleIdTokenCredential.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // JVM unit tests. org.json: Android's copy is only a stub off-device, and
    // SessionJournal's meta.json goes through it.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
