plugins {
    id("buildsrc.convention.kotlin-jvm")
}

group = "pl.umamusume"
version = rootProject.version

dependencies {
    testImplementation(platform(libs.junitBom))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.bundles.junitRuntime)
}