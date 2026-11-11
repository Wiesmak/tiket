allprojects {
    version = providers.gradleProperty("releaseVersion").orElse("0.0.1-SNAPSHOT").get()
    group = "pl.umamusume"
}
