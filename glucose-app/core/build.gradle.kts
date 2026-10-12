plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Plain-Kotlin code shared by the phone app and the Ray server (no Android):
// stored rows, sync format + merge, parsing, Ray's tools and prompt pieces.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("androidx.room:room-common:2.6.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // Android ships org.json; the server adds the same library itself.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
