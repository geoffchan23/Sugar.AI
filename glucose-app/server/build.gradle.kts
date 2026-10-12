plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// The Ray server (cloud Ray): keeps the master copy of Francine's data and,
// from phase 3, answers for every device. Runs on its own Oracle VM; see
// server/README.md. Shares sync, parsing and Ray's prompt with the phones (:core).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.geoffchan.ray.MainKt")
    applicationName = "ray"
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
