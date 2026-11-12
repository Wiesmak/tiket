plugins {
    id("org.jetbrains.dokka") version "2.2.0"
}

dokka { dokkaPublications.html { offlineMode.set(true) } }

dependencies {
    dokka(project(":tiket-core"))
    dokka(project(":tiket-print-api"))
    dokka(project(":tiket-print-console"))
    dokka(project(":tiket-print-serial"))
}

allprojects {
    version = providers.gradleProperty("releaseVersion").orElse("0.0.1-SNAPSHOT").get()
    group = "pl.umamusume"
}
