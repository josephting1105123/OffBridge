import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

// Release signing reads from a gitignored keystore.properties at the repo root (never committed):
//   storeFile / storePassword / keyAlias / keyPassword
// When absent (fresh clone / CI without secrets) no release signingConfig is created and none is
// applied below, so assembleRelease produces an UNSIGNED APK — usable for R8/CI shrink validation
// only, not for distribution. A real Play release must supply this file (there is no debug-signing
// fallback; installing an unsigned release APK will fail).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.EntropicSoftwareLab.ClipboardShare"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.EntropicSoftwareLab.ClipboardShare"
        minSdk = 29
        targetSdk = 36
        versionCode = 28
        versionName = "3.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 code shrinking/obfuscation + resource shrinking. Safe here: all JSON is org.json
            // (string-literal keys, no reflective (de)serialization) and there is no getIdentifier/
            // Class.forName dynamic lookup, so shrinking can't strip a referenced symbol/resource.
            // See proguard-rules.pro for the defensive keep set (Tink, ML Kit, ZXing, Room).
            isMinifyEnabled = true
            isShrinkResources = true
            // Sign with the real release key when keystore.properties is present; otherwise no
            // signingConfig is applied and the release APK is UNSIGNED (R8/CI validation only, not
            // installable/distributable).
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The compose-compiler plugin (org.jetbrains.kotlin.plugin.compose) IS applied via the catalog
    // alias in the plugins {} block above; it is pinned to the "kotlin" version (2.0.0 in the
    // catalog). Whether that pin matches AGP 9's built-in Kotlin needs a validated bump — see the
    // FLAG note. Incremental adoption: Compose and the existing XML Views coexist via
    // ComposeView / AndroidView interop.
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)

    // Jetpack Compose (incremental adoption alongside the existing XML Views)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.security.crypto)

    // V3.0 off-grid transport
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Bundled model — works offline, no Play-Services availability check
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.facebook.shimmer)

    // V4 transport ladder persistence (§6/§7 of the V4 plan)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}