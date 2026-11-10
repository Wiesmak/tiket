plugins {
    id("buildsrc.convention.kotlin-jvm")
}

group = "pl.umamusume"
version = "unspecified"

dependencies {
    testImplementation(platform(libs.junitBom))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.bundles.junitRuntime)
}