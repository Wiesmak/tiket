plugins {
    id("buildsrc.convention.spring-boot")
}

group = "pl.umamusume"
version = rootProject.version
description = "tiket-backend"

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("boot")
}

val mockitoAgent = configurations.create("mockitoAgent") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(libs.guava) // CVE-2026-102554: override vulnerable transitive 33.6.0.
    implementation(project(":tiket-print-api"))
    implementation(project(":tiket-print-console"))
    implementation(project(":tiket-core"))
    implementation(project(":tiket-contracts"))
    implementation(libs.bundles.backend)
    runtimeOnly(libs.bundles.backendRuntime)
    testImplementation(libs.bundles.backendTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
    mockitoAgent(libs.mockitoCore)
}

tasks.withType<Test>().configureEach {
    // Load Mockito at JVM startup instead of relying on dynamic self-attachment.
    jvmArgs("-javaagent:${mockitoAgent.singleFile.absolutePath}")
}
