plugins {
    id("buildsrc.convention.kotlin-jvm")
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