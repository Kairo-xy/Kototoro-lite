# Kototoro Lite — Safe Size-Reduction Audit

Branch: `chatgpt/kototoro-lite-first-pass`

## Current verified direction

- The Android build is configured for `arm64-v8a` only.
- Native image-upscaling binaries were removed; Anime4K shader enhancement remains.
- Unused x86/x86_64 jlibtorrent JARs and the duplicate CloudStream pre-release JAR were removed.
- The current debug build workflow is intended to report APK contents and native-library sizes.

## Findings: video and TVBox are not isolated yet

The repository tree and `settings.gradle` show video playback is integrated across the app rather than being a standalone optional module. The Android app contains:

- Media3 playback engine and HLS/DASH/data-source dependencies.
- CloudStream runtime and playback-selection code.
- TVBox runtime, parser, action-host activity, and QuickJS integration.
- Video downloads, torrent streaming, subtitles, danmaku, DLNA, and related tests.
- Video-specific manifest entries, permissions, UI assets, settings, and navigation.

The app also includes `:cloudstream-shared` and `:source-tvbox` in the Gradle project. Removing only Media3 or deleting the `video/` directory would likely break compilation, dependency injection, navigation, manifests, or extension compatibility.

## Safe removal order

1. **Get a green baseline build first.** Do not combine a broad feature deletion with the current upscaler cleanup.
2. **Measure the APK.** Use the workflow's APK size report and largest-entry report; distinguish repository checkout size from actual packaged APK size.
3. **Map feature roots.** Trace navigation, Hilt bindings, manifest components, services, workers, settings, download types, parser APIs, and extension ABI dependencies.
4. **Choose a scope.** If Kototoro Lite is to be strictly manga-only, remove video/TVBox/CloudStream as a coordinated feature migration, not as isolated dependency deletions.
5. **Preserve manga compatibility.** Keep manga source APIs, source loading/install, reader rendering, offline chapter downloads, library/history/bookmarks, backup/restore, and existing user data.
6. **Remove in coherent batches.** Update Gradle modules/dependencies, app source, DI modules, manifest, navigation, resources, and tests together. Search all remaining references after each batch.
7. **Validate.** Run the Android debug build and relevant tests; inspect the final APK's native ABI and size report.

## Explicitly not safe to remove based on current evidence

- `cloudstream3-library-jvm-1.0.1.jar`: the Gradle build generates a sanitized runtime JAR from it.
- `jlibtorrent-2.0.12.9.jar` and `jlibtorrent-android-arm64-2.0.12.9.jar`: still declared by the app's Gradle configuration.
- Media3, CloudStream, and TVBox modules/dependencies: still connected to active source files and runtime paths.

## Current build status

At the time of this audit, workflow run `38887355803` was pending/queued; a successful build has not been confirmed. Treat all cleanup as provisional until CI passes.
