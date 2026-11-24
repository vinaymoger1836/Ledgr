# Pinned versions

Single source of truth is `gradle/libs.versions.toml`; this table records what each pin is, its licence, and why a
version is not the newest. Checked against Google Maven / Maven Central / Gradle on **2026-11-24**. All licences are
permissive (docs/02 §2). New dependencies are added here in the same change.

## Toolchain

| Item | Version | Licence | Notes |
|---|---|---|---|
| Gradle (wrapper) | 9.6.0 | Apache-2.0 | Minimum required by AGP 9.5 (9.3.0 fails with `NoClassDefFoundError: ProjectTypeBinding`). 9.9.2 exists; lint reports it as available, 9.6.0 is AGP's own default. |
| Android Gradle Plugin | 9.5.2 | Apache-2.0 | Built-in Kotlin: no `kotlin-android` plugin. Needs build-tools 36.0.0 and JDK 17+. |
| Kotlin | 2.4.21 | Apache-2.0 | JVM target 17 (runs on Android Studio's JBR 21). |
| KSP | 2.3.14 | Apache-2.0 | Room and Hilt compilers. |
| compileSdk / targetSdk / minSdk | 37 / 37 / 29 | — | Installed platform is `android-37.0`. |
| JDK used to run Gradle | Android Studio JBR (21) | GPL-2.0+CPE | Not shipped; build tool only. |

## Libraries

| Library | Version | Licence | Used in |
|---|---|---|---|
| Compose BOM | **2026.09.00** | Apache-2.0 | `:app`. **Deliberately not 2026.11.01:** Compose 1.13 requires compileSdk 37.1, which is not installed. Move to 2026.11.01 after installing platform 37.1 (Android Studio SDK Manager) and setting `compileSdk` accordingly. |
| Material 3, Foundation, UI, material-icons-core | via BOM | Apache-2.0 | `:app` |
| androidx.activity-compose | 1.13.0 | Apache-2.0 | `:app` |
| androidx.navigation-compose | 2.10.2 | Apache-2.0 | `:app` |
| androidx.lifecycle (runtime/viewmodel-compose, process) | 2.11.1 | Apache-2.0 | `:app` |
| androidx.core-ktx / core-splashscreen | 1.19.1 / 1.2.0 | Apache-2.0 | `:app`, `:data` |
| androidx.biometric | 1.1.0 | Apache-2.0 | `:app` (newer releases are alpha/rc) |
| Hilt (android, compiler) | 2.60.1 | Apache-2.0 | `:app`, `:data` |
| androidx.hilt:hilt-navigation-compose | 1.4.0 | Apache-2.0 | `:app` (`hiltViewModel` now lives in `androidx.hilt.lifecycle.viewmodel.compose`) |
| Room (runtime, ktx, compiler, Gradle plugin) | 2.8.5 | Apache-2.0 | `:data` |
| androidx.sqlite | 2.7.1 | Apache-2.0 | `:data` |
| sqlcipher-android | 4.20.0 | BSD-style (Zetetic, SQLCipher Community Edition) | `:data`. Ships native libs for arm64-v8a, armeabi-v7a, x86, x86_64. Keep the Zetetic copyright notice in any "licences" screen. |
| androidx.datastore-preferences | 1.3.0 | Apache-2.0 | `:data` |
| androidx.work-runtime-ktx | 2.12.0 | Apache-2.0 | `:data` (first used in P2) |
| Vico compose-m3 | 3.3.1 | Apache-2.0 | `:app` (first used in P4/P5) |
| kaml | 0.104.0 | Apache-2.0 | `:domain` (rule YAML) |
| kotlinx-serialization-json | 1.13.0 | Apache-2.0 | `:golden` |
| kotlinx-coroutines | 1.11.0 | Apache-2.0 | `:app`, `:data` |

PdfBox-Android, Apache POI HSSF and any XLSX helpers are added in P6, with their licences, here.

## Network check

No library above declares `INTERNET`. WorkManager and Biometric add `ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `USE_BIOMETRIC` and `USE_FINGERPRINT`. `./gradlew goldenCheck` fails
if any merged manifest (debug or release) ever declares `INTERNET`.
