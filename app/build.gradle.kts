plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.reopen.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.reopen.app2"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "2.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
