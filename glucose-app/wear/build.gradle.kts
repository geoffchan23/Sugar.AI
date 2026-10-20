plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Her Pixel Watch 2: voice log → her phone (the main phone) over the Wearable
// Data Layer. applicationId MUST match the phone app's, and both must be
// signed with the same (debug) key, or the phone never hears the watch.
android {
    namespace = "com.geoffchan.glucosewidget.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geoffchan.glucosewidget"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    // Wear Compose 1.4.x / Tiles 1.4.x / ProtoLayout 1.2.x: the newest lines that
    // build with this project's AGP 8.7 + compileSdk 35.
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    // The ✓ on the Big Glucose face after a voice log (LogStatusComplicationService).
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Retries entries held while her phone was out of reach.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
