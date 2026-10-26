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

    // Optional release signing: only applied when all TREXTRACK_* properties are set.
    val storeFileProp = providers.gradleProperty("TREXTRACK_STORE_FILE")
    val storePasswordProp = providers.gradleProperty("TREXTRACK_STORE_PASSWORD")
    val keyAliasProp = providers.gradleProperty("TREXTRACK_KEY_ALIAS")
    val keyPasswordProp = providers.gradleProperty("TREXTRACK_KEY_PASSWORD")
    val hasReleaseSigning =
        storeFileProp.isPresent &&
            storePasswordProp.isPresent &&
            keyAliasProp.isPresent &&
            keyPasswordProp.isPresent

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFileProp.get())
                storePassword = storePasswordProp.get()
                keyAlias = keyAliasProp.get()
                keyPassword = keyPasswordProp.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
