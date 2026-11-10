plugins {
    id("buildsrc.convention.java")
}

group = "pl.umamusume"
version = "unspecified"

dependencies {
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.bundles.junitRuntime)
}