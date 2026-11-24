// :domain — pure Kotlin/JVM. No android.* and no Android Gradle plugin here (ADR-010).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(libs.kaml)
    // sender_headers is stored as a JSON list (docs/05 §1 `account`).
    implementation(libs.kotlinx.serialization.json)
    // XLSX is read with an XmlPullParser (ADR-008). Android's platform ships org.xmlpull.v1, so on the phone this is only a
    // compile-time interface; the JVM golden runner brings its own implementation (kxml2).
    compileOnly(libs.xmlpull)
}
