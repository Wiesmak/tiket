plugins {
    id("org.jetbrains.dokka")
    id("buildsrc.convention.kotlin-jvm")
}

dokka {
    dokkaPublications.html { offlineMode.set(true) }
    dokkaSourceSets.named("main") {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/Wiesmak/tiket/tree/master/tiket-print-serial/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

group = "pl.umamusume"
version = rootProject.version

dependencies {
    implementation(project(":tiket-print-api"))
    implementation(libs.bundles.serialPrinting)
    testImplementation(platform(libs.junitBom))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.bundles.junitRuntime)
}

// Local-only opt-in diagnostic; never participates in build/check or CI.
tasks.register<JavaExec>("serialSmoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("pl.umamusume.tiket.printing.serial.SerialSmoke")
    jvmArgs("-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED")
    val port = providers.gradleProperty("serialSmokePort")
    doFirst { args("--port", port.get()) }
}
