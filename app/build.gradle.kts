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
        versionCode = 10
        versionName = "1.4.3"
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
