plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oberoi.tvblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oberoi.tvblocker"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"
    }

    signingConfigs {
        create("release") {
            // CI decodes release.keystore.b64 to this path before building.
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("TVB_STORE_PASS") ?: "tvblocker2026"
                keyAlias = System.getenv("TVB_KEY_ALIAS") ?: "tvblocker"
                keyPassword = System.getenv("TVB_KEY_PASS") ?: "tvblocker2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // Debug builds also use the stable key, so ANY variant installs
            // over any previous one without a signature conflict.
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { }
