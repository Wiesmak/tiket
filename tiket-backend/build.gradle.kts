import com.google.protobuf.gradle.id

plugins {
    id("buildsrc.convention.spring-boot")
}

group = "pl.umamusume"
version = "0.0.1-SNAPSHOT"
description = "tiket-backend"

dependencies {
    implementation(libs.bundles.backend)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.bundles.backendTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

protobuf {
    plugins {
        id("grpc")
    }
}
