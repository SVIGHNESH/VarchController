plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// release-please bumps this line; versionCode follows from it.
val appVersion = "0.6.0" // x-release-please-version

// The release keystore comes from the environment, so it never lives in the repo.
// Without it, assembleRelease still builds, and leaves the APK unsigned.
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")

android {
    namespace = "dev.varch.controller"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.varch.controller"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.substringBefore('-').split('.').map(String::toInt)
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                // A PKCS12 keystore uses one password for the store and the key.
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // targetSdk stays at 36 until the local-network permission that 37
        // enforces has been tested on a real device.
        disable += "OldTargetApi"
        warningsAsErrors = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(bom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
