plugins {
    id("org.jetbrains.dokka")
    id("buildsrc.convention.kotlin-jvm")
}

dokka {
    dokkaPublications.html { offlineMode.set(true) }
    dokkaSourceSets.named("main") {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/Wiesmak/tiket/tree/master/tiket-print-console/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

group = "pl.umamusume"
version = rootProject.version

dependencies {
    implementation(project(":tiket-print-api"))
    testImplementation(platform(libs.junitBom))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.bundles.junitRuntime)
}