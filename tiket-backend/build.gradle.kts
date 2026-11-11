plugins {
    id("buildsrc.convention.spring-boot")
}

group = "pl.umamusume"
version = "0.0.1-SNAPSHOT"
description = "tiket-backend"

dependencies {
    implementation(project(":tiket-print-api"))
    implementation(project(":tiket-print-console"))
    implementation(project(":tiket-core"))
    implementation(project(":tiket-contracts"))
    implementation(libs.bundles.backend)
    runtimeOnly(libs.bundles.backendRuntime)
    testImplementation(libs.bundles.backendTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
