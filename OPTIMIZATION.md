# VkDownloader — size optimization

## Changes

- Enabled R8 bytecode shrinking/optimization and Android resource shrinking for release builds.
- Replaced OkHttp with `HttpURLConnection` for GET, POST, redirects, and binary segment downloads; removed OkHttp/Okio from the runtime dependency graph.
- Replaced AppCompat Activity/dialog/theme with platform Android UI; removed AppCompat.
- Replaced `NotificationCompat` with platform `Notification.Builder` (API 24+ is the app minimum); removed AndroidX Core.
- Replaced `lifecycleScope` with a small Activity-owned coroutine scope cancelled in `onDestroy`; removed Lifecycle Runtime.
- Replaced density-specific launcher PNGs/adaptive icon assets with one vector drawable.
- Removed an unused version catalog containing dependencies that were not referenced by the app build.
- Corrected the Gradle wrapper to Gradle 8.7, compatible with Android Gradle Plugin 8.5.2; the previous wrapper requested a Gradle 9.0 milestone.
- Removed machine-specific `local.properties` from the portable project.

## Remaining runtime dependency

`kotlinx-coroutines-android` remains because HLS segment downloads run concurrently and the service uses coroutine scopes/dispatchers. Android platform APIs handle HTTP, notifications, storage, media extraction, muxing, and direct MP4 downloads.

## Build

Run `./gradlew assembleRelease` in an environment with JDK 17+, Android SDK Platform 34, and network access (or cached Gradle/dependencies). The release APK is expected at `app/build/outputs/apk/release/app-release.apk`.

R8/resource shrinking is enabled, but APK size and runtime behavior have **not** been verified in this sandbox: the supplied `local.properties` pointed to a host-specific SDK path, no Android SDK is installed here, and the Gradle wrapper distribution could not be downloaded because network DNS is unavailable. A clean build and device smoke test are still required before treating the APK as release-ready.
