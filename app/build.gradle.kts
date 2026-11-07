plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamewatchdog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gamewatchdog"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "1.6.2"
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
}
