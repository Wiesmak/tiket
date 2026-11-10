import com.google.protobuf.gradle.id

plugins {
    id("buildsrc.convention.java")
    `java-library`
    id("com.google.protobuf")
}

group = "pl.umamusume"
version = "0.0.1-SNAPSHOT"

dependencies {
    api(platform(libs.springBootBom))
    api(libs.bundles.grpc)
}

protobuf {
    // This library deliberately does not apply the Spring Boot plugin.
    protoc { artifact = libs.protobufProtoc.get().toString() }
    plugins {
        id("grpc") { artifact = libs.grpcJavaCodegen.get().toString() }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { id("grpc") }
            notCompatibleWithConfigurationCache("Protobuf retains Project and SourceDirectorySet state.")
        }
    }
}
