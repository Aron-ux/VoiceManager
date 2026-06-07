plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.company.vehiclevoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.company.vehiclevoice"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0-m1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
