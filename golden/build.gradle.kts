// :golden — JVM-only runner for the golden contract (docs/12 §4).
// Fixtures: golden/fixtures, expected output: golden/expected, real data: golden/private (git-ignored).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.serialization.json)
    // XML pull parser implementation for the XLSX reader (the phone has one in the platform).
    runtimeOnly(libs.xmlpull)
    runtimeOnly(libs.kxml2)
    // P6, golden only (never shipped): author the synthetic PDF (encrypted, multi-page) and .xls fixtures, and read the
    // owner's private PDFs. The app itself reads PDFs with PdfBox-Android.
    implementation(libs.pdfbox.jvm)
    implementation(libs.poi.hssf)
}

application {
    mainClass.set("com.ledgr.app.golden.MainKt")
}

val goldenDir = layout.projectDirectory
val rulesDir = rootProject.layout.projectDirectory.dir("app/src/main/assets/rules")

// `./gradlew goldenCheck` depends on this. It always runs: a green golden check must never be "up-to-date".
tasks.register<JavaExec>("runGolden") {
    group = "verification"
    description = "Feeds fixtures through :domain and compares JSON with golden/expected byte-for-byte."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(
        "--fixtures=${goldenDir.dir("fixtures").asFile}",
        "--expected=${goldenDir.dir("expected").asFile}",
        "--rules=${rulesDir.asFile}",
        "--private=${goldenDir.dir("private").asFile}",
    )
    // `./gradlew :golden:runGolden -PgoldenEmit` also writes each case's actual output to golden/build/emitted, to read
    // and copy into golden/expected by hand. It never touches golden/expected.
    if (project.hasProperty("goldenEmit")) {
        args("--emit=${layout.buildDirectory.dir("emitted").get().asFile}")
    }
    inputs.dir(goldenDir.dir("fixtures"))
    inputs.dir(goldenDir.dir("expected"))
    inputs.dir(rulesDir)
    outputs.upToDateWhen { false }
}
