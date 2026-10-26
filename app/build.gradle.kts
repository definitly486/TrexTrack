
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.definitly.trextrack"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.definitly.trextrack"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.gradleProperty("TREXTRACK_STORE_FILE").get()
            )
            storePassword =
                providers.gradleProperty("TREXTRACK_STORE_PASSWORD").get()
            keyAlias =
                providers.gradleProperty("TREXTRACK_KEY_ALIAS").get()
            keyPassword =
                providers.gradleProperty("TREXTRACK_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

