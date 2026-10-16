# Kototoro Lite optimization plan

This branch is a working optimization fork, not yet a verified release build.

## Preserve
- Manga/source extensions and source management
- Reader UI and image loading
- Library, categories, bookmarks, history, and reading progress
- Downloads/offline reading and backup/restore
- Existing database schema and migrations

## Candidate removals
These are intentionally listed as separate feature groups because they have cross-references and native/dependency wiring.

1. **Video playback / danmaku** — `app/src/main/kotlin/org/skepsun/kototoro/video/`, manifest activity `VideoPlayerActivity`, Media3/NextLib/danmaku dependencies, video-related tests.
2. **TVBox** — `app/src/main/kotlin/org/skepsun/kototoro/core/parser/tvbox/`, `app/src/main/java/com/github/tvbox/`, JSON TVBox models/repositories, manifest activity, QuickJS/runtime compatibility and tests.
3. **CloudStream** — `app/src/main/kotlin/org/skepsun/kototoro/cloudstream/`, `:cloudstream-shared`, bundled CloudStream jars, video playback adapters, and sanitizing Gradle tasks.
4. **OCR / translation** — `app/src/main/kotlin/org/skepsun/kototoro/reader/translate/`, ML Kit OCR/translation, ONNX Runtime, OpenCV, Paddle/Manga OCR assets and model-download workers.
5. **Image upscaling** — RealCUGAN/RealESRGAN Kotlin engines, `app/src/main/cpp/realcugan*`, associated shaders/assets, Vulkan/NCNN CMake wiring, and the RealCUGAN dependency.

## Safety gates before deleting a group
- Search all Kotlin/Java/XML/Gradle/CMake references, including dependency injection and tests.
- Remove associated navigation/settings/manifest entries and stale resources together.
- Keep manga page rendering and normal image decode paths unchanged.
- Run `./gradlew :app:assembleDebug` and unit tests.
- Inspect APK contents and record APK size + SHA-256 before/after.
- Do not claim size savings until successful APK artifacts are measured.

## Current status
- App display name changed to Kototoro Lite.
- ABI target restricted to `arm64-v8a` for the intended device.
- ARM64-only jlibtorrent native runtime is declared.
- GitHub Actions workflow added for debug APK creation and size/checksum reporting.
- Initial Android SDK setup failure was addressed; latest build must still be checked for a successful result.
