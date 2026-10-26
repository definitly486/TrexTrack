
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
            val store = providers.gradleProperty("TREXTRACK_STORE_FILE")
            val storePass = providers.gradleProperty("TREXTRACK_STORE_PASSWORD")
            val alias = providers.gradleProperty("TREXTRACK_KEY_ALIAS")
            val keyPass = providers.gradleProperty("TREXTRACK_KEY_PASSWORD")
            if (store.isPresent && storePass.isPresent && alias.isPresent && keyPass.isPresent) {
                storeFile = file(store.get())
                storePassword = storePass.get()
                keyAlias = alias.get()
                keyPassword = keyPass.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
