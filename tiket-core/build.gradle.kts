import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.dokka")
    id("buildsrc.convention.kotlin-jvm")
}

dokka {
    dokkaPublications.html { offlineMode.set(true) }
    dokkaSourceSets.named("main") {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/Wiesmak/tiket/tree/master/tiket-core/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

// Independent compiler module: consumes the JAR, with no Kotlin friend access to main.
val consumerCompatibility = sourceSets.create("consumerCompatibility")
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest)
    add(consumerCompatibility.implementationConfigurationName, files(tasks.named("jar")))
}
val compileConsumerCompatibility = tasks.named<KotlinCompile>("compileConsumerCompatibilityKotlin") {
    compilerOptions.moduleName.set("tiket-external-consumer")
    doFirst {
        check(friendPaths.isEmpty) { "Consumer compatibility check must not have friend access" }
    }
}
tasks.named("check") {
    dependsOn(compileConsumerCompatibility)
}
