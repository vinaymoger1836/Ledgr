// :data — Room + SQLCipher, key handling, DataStore settings, (later) SMS source, workers, readers.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.ledgr.app.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room schema exports are source (docs/05); they are committed so migrations can be reviewed.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Encrypted database
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // The statement table pages straight from Room (docs/07 §3). `api` so :app can collect the PagingData.
    api(libs.androidx.room.paging)
    api(libs.androidx.paging.common)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    // Settings and background work (the workers themselves live in :app)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
