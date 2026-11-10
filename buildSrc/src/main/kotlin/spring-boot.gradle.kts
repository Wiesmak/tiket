package buildsrc.convention

import com.google.protobuf.gradle.GenerateProtoTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<GenerateProtoTask>().configureEach {
    notCompatibleWithConfigurationCache("The protobuf plugin retains Project and SourceDirectorySet state.")
}
