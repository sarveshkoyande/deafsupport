plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echosense.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.echosense.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-gentletone"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

dependencies {
    // ARCore: camera pose + Depth API used to detect obstacle distance.
    implementation("com.google.ar:core:1.42.0")
}
