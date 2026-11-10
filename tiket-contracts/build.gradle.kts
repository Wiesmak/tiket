import com.google.protobuf.gradle.id

plugins {
    id("buildsrc.convention.java")
    `java-library`
    id("com.google.protobuf")
}

group = "pl.umamusume"
version = "0.0.1-SNAPSHOT"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    api("com.google.protobuf:protobuf-java")
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
}

protobuf {
    // This library deliberately does not apply the Spring Boot plugin.
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { id("grpc") }
            notCompatibleWithConfigurationCache("Protobuf retains Project and SourceDirectorySet state.")
        }
    }
}
