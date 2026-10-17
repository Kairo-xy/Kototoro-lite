# Kototoro Lite: CI builds and signing

## Workflows

- **Kototoro Lite Android build** (`.github/workflows/kototoro-lite-android-build.yml`): runs on pull requests
  and pushes to `devel`. It builds the debug APK, then the minified release APK, in separate Gradle runs
  (building both at once ran the Kotlin compiler out of memory). Both APKs are ARM64-only and are uploaded
  as artifacts (`kototoro-lite-arm64-debug`, `kototoro-lite-arm64-release`) for 7 days.
- **Kototoro Lite release** (`.github/workflows/kototoro-lite-release.yml`): runs when a `lite-v*` tag is created
  (for example by publishing a release in the GitHub UI) or manually. It builds the signed release APK and
  attaches it, with a SHA-256 checksum file, to the GitHub Release.

## Signing

The release APK is signed with a key kept outside the repository. Add these four repository secrets
(Settings > Secrets and variables > Actions > Secrets):

- `RELEASE_KEYSTORE_BASE64`: the keystore file encoded with `base64 -w0`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

If any of them is missing, the build workflow still passes but produces an unsigned release APK
(`app-arm64-v8a-release-unsigned.apk`), which Android will not install. The release workflow fails instead.
Never commit the keystore or its passwords, and keep a backup of the keystore: without it, updates cannot be
published over an installed copy.

## Application id

Kototoro Lite uses `org.skepsun.kototoro.lite` (debug: `org.skepsun.kototoro.lite.debug`), so it installs next
to the original Kototoro. The code namespace remains `org.skepsun.kototoro`.
