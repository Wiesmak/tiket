plugins { id("buildsrc.convention.spring-boot") }
group = "pl.umamusume"
version = "0.0.1-SNAPSHOT"
description = "tiket-backend"
dependencies {
    implementation(project(":tiket-print-api"))
    implementation(project(":tiket-print-console"))
    implementation(project(":tiket-core"))
    implementation(project(":tiket-contracts"))
    implementation(libs.bundles.backend)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    runtimeOnly(libs.postgresql)
    testImplementation(libs.bundles.backendTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
