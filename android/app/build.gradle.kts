plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "no.hermann.g2meldinger"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.hermann.g2meldinger"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    // Fast signeringsnøkkel slik at Obtainium kan oppdatere appen.
    // Nøkkelen ligger kryptert i repoet og dekrypteres i GitHub Actions med KEYSTORE_PASSWORD (Secrets).
    signingConfigs {
        create("release") {
            storeFile = file("../signing/g2meldinger.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "g2meldinger"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
