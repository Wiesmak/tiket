plugins {
    id("buildsrc.convention.spring-boot")
}

group = "pl.umamusume"
version = "0.0.1-SNAPSHOT"
description = "tiket-backend"

dependencies {
    implementation(project(":tiket-core"))
    implementation(project(":tiket-contracts"))
    implementation(libs.bundles.backend)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.bundles.backendTest)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly(libs.junitPlatformLauncher)
}
