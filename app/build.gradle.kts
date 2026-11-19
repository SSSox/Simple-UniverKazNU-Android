import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing stays outside version control: keystore.properties (storeFile, storePassword, keyAlias,
// keyPassword) next to settings.gradle.kts. Without it the release build is produced unsigned.
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.univerflow.kaznu"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.univerflow.kaznu"
        minSdk = 24
        targetSdk = 37
        versionCode = 31
        versionName = "2.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The app speaks Russian (default), Kazakh and English; libraries' other translations would only add ~0.5 MB.
    androidResources {
        localeFilters += listOf("ru", "kk", "en")
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
            // v3 lets a later key change keep updates working on Android 9+ (apksigner rotate).
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // R8 code and resource optimization; keep rules live in src/main/keepRules.
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("release")
        }
        // The release build (R8 and all) signed with the debug key: it installs over a debug build with
        // `adb install -r` and keeps the app's data, so release-only behaviour is checked on a configured emulator.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true }
    bundle { language { enableSplit = false } }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
