# Releasing & In-App Update Architecture

This document describes VELVET's release workflow, keystore security, in-app update mechanisms, and dependency automation.

---

## 1. Release Keystore & Certificate Immutability

### Keystore Location and Setup
- **File:** `keystore/velvet.jks`
- **Key Alias:** `velvet`
- **Default Password:** `velvetapp` (used for both store password and key password)
- **CI Secrets (Optional Override):**
  - `VELVET_KEYSTORE_PASSWORD`
  - `VELVET_KEY_ALIAS`
  - `VELVET_KEY_PASSWORD`
  - `VELVET_KEYSTORE` (custom path if not using repository keystore)

### Immutability of Signing Keys
Android strictly enforces that updates installed over an existing package must be signed by the identical certificate history (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). If the signing key is lost or replaced:
1. Existing users cannot install updates without uninstalling first.
2. Uninstalling deletes all user data, playlists, cached audio, and settings.
3. Therefore, `keystore/velvet.jks` is stored directly in the repository and serves as the permanent release key for all builds.

### In-App Certificate Verification
Before launching `PackageInstaller`, `AppUpdater.verifySigningCertificate(context, apkFile)` reads the certificate signatures of the downloaded APK using Android's `PackageManager.getPackageArchiveInfo` and compares them against the currently running application (`PackageManager.getPackageInfo`). If the certificates mismatch:
- The downloaded file is deleted immediately.
- A `CertificateMismatchException` is thrown.
- The update is rejected with an error message to prevent installation failures.

---

## 2. Checksum Integrity (SHA-256)

Every release published through GitHub Actions computes the SHA-256 hash of `velvet.apk` using `sha256sum` and publishes it directly into the release description:
```markdown
**SHA-256:** `<64-character-hex>`
```

### In-App Verification
1. `ReleaseCheck.extractSha256(releaseNotes)` extracts the 64-character hex hash from the GitHub release body.
2. When the user taps "Download & Install", `AppUpdater.downloadApk` downloads the APK to cache.
3. `AppUpdater.verifySha256(file, expectedSha256)` computes the SHA-256 hash of the downloaded file.
4. If the checksum does not match, the APK is immediately deleted and a `Sha256MismatchException` is thrown.

---

## 3. How to Publish a Release

1. **Bump Version:**
   In `app/build.gradle.kts`, update the `versionName`:
   ```kotlin
   versionName = "1.1"
   ```
2. **Commit and Push:**
   Commit the change to `main`:
   ```bash
   git add app/build.gradle.kts
   git commit -m "chore: bump version to 1.1"
   git push origin main
   ```
3. **Tag the Release:**
   Tag the commit with a `v`-prefixed tag and push the tag:
   ```bash
   git tag v1.1
   git push origin v1.1
   ```
4. **Automated Pipeline:**
   GitHub Actions workflow (`.github/workflows/build.yml`) triggers automatically on tags matching `v*`:
   - Checks out the repository.
   - Sets up Java 17 Temurin and Gradle 8.11.1.
   - Runs the full test suite (`gradle testReleaseUnitTest`).
   - Builds and signs the release APK (`assembleRelease`) with R8 optimization.
   - Computes the SHA-256 checksum.
   - Creates a GitHub Release for tag `v1.1` with release notes and `velvet.apk` asset.
   - Uploads `velvet-apk` as an action artifact.

---

## 4. In-App Update Detection

VELVET checks for updates using the unauthenticated GitHub Releases API:
- **Repository API:** `https://api.github.com/repos/Rkjbad50/velvet/releases`
- **Extractor API:** `https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest`

### Features
- **1-Hour In-Memory Cache:** Successful check results are cached for 1 hour to prevent hitting GitHub's unauthenticated IP rate limits (60 requests/hr).
- **Rate Limit & Offline Grace:** Returns cached update info marked with `isRateLimited = true` or `isOffline = true` so the user is never stranded.
- **Semantic Versioning (`SemVer`):** Compares versions numerically (`0.24.9 < 0.24.10`, `v2.7 > 2.6`) and handles pre-releases (`2.7 > 2.7-beta1`).
- **Pre-releases Toggle:** "Include pre-releases" toggle in Settings allows opting into alpha, beta, and RC builds.
- **Dependency Tracking:** Tracks both the VELVET application version and upstream `NewPipeExtractor` version. If YouTube changes its backend and NewPipe releases a fix, VELVET flags the extractor update (`UpdateTrigger.EXTRACTOR`).
- **Background Periodic Check:** `UpdateCheckJobService` runs a persisted `JobScheduler` task every 24 hours on unmetered Wi-Fi and posts a low-priority, silent notification if an update is found.

---

## 5. Automated Dependency Updates

- **Dependabot (`.github/dependabot.yml`):** Scans the Gradle version catalog weekly for updates to `NewPipeExtractor` and `androidx.media3`.
- **Renovate (`.github/renovate.json`):** Configured with weekend schedules and grouped streaming dependencies for clean, grouped PRs.
