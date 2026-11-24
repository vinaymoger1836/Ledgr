// Root build: plugin versions only, plus the single `goldenCheck` entry point
// (docs/12 §4): merged-manifest INTERNET check + the :golden JVM runner.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

tasks.register("goldenCheck") {
    group = "verification"
    description = "Runs the golden contract: no INTERNET in merged manifests + :golden fixtures vs expected."
    dependsOn(
        ":app:checkNoInternetDebug",
        ":app:checkNoInternetRelease",
        ":golden:runGolden",
    )
}
