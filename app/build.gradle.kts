plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.exitreminder.gpstester"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.exitreminder.gpstester"
        minSdk = 26
        targetSdk = 34
        versionCode = 31
        versionName = "3.2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // OpenStreetMap mit osmdroid (kein API-Key nötig!)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Coroutines für async Snap to Road
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
