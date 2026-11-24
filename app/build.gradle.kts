import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ledgr.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ledgr.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // P0: unsigned, unminified. Signing and R8 arrive in P7 (keystore.properties stays git-ignored).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Lint results are build output (git-ignored); keep the report next to the other build files.
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Charts are used from P4; present now so the merged-manifest check covers them.
    implementation(libs.vico.compose.m3)

    // The statement-style table (P5) collects Room's PagingSource as lazy items.
    implementation(libs.androidx.paging.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
}

// ───────────────────────── golden check (a): no INTERNET in any merged manifest ─────────────────────────
// docs/09 §2, CLAUDE.md non-negotiable rule 1. Deliberately no `tools:node="remove"` in our manifest, so a
// library that declares INTERNET fails this check instead of being silently masked.
abstract class CheckNoInternetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun check() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)

        val offenders = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
            .flatMap { tag ->
                val nodes = doc.getElementsByTagName(tag)
                (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            }
            .filter { it.getAttributeNS(androidNs, "name") == "android.permission.INTERNET" }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Merged manifest for '${variantName.get()}' declares android.permission.INTERNET " +
                    "(${mergedManifest.get().asFile}). The app is on-device only; find the library that adds it.",
            )
        }
        logger.lifecycle("[ OK ] merged manifest '${variantName.get()}': no INTERNET permission")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        tasks.register<CheckNoInternetTask>("checkNoInternet$cap") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest declares INTERNET."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variantName.set(variant.name)
        }
    }
}
