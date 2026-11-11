# IPTV Android conventions

Use native Kotlin and Compose. Keep packages under cn.hdfk7.android.iptv:
- app: Application and Activity host
- core/model: plain models, no HTTP/JSON
- core/data/local, network, repository: persistence, parsing, refresh
- core/ui/theme: shared Compose theme
- feature/player: player state, ViewModel, screen and named components

Follow these Kotlin readability conventions: four spaces, multiline braced control-flow bodies, explicit return types, explicit lambda parameters, named callbacks and Compose slot arguments, no trailing lambda syntax, one top-level root per UI composable. Put text in strings.xml and graphics in drawable resources. Separate behavior from presentation.

Target Android 14 (API 34); do not call newer platform APIs without guards. Unit tests use JUnit 5 and names ending Tests. Do not embed channel lists; follow the explicit signing rules below. Build and run unit tests with ./gradlew :app:testDebugUnitTest :app:assembleRelease. For every device or emulator test, install the signed Release APK generated from the current source and verify the installed version before testing. Use the packaged Release APK for all UI checks and screenshots.

## Source and playback behavior

`feature/source` owns source management state and dialogs. One source is active at a time. Aggregate program URLs by normalized program name, including across source contributions in the favorites source. Keep favorite contributions separately so refreshed URLs retain their origins. Next/previous always change the program. Playback probes candidate URLs in order and stops probing when one is ready. Timeouts are configured by STREAM_*_TIMEOUT_MS in gradle.properties.

## Git and packaging

For production releases, run the repository's `upload-release.bat` on Windows or `upload-release.sh` on macOS/Linux. Use the script's default clean build and upload flow unless the user explicitly requests another mode. After publishing, verify the public `latest.json` and confirm that the downloadable APK matches the signed local artifact.

Create or push a Git release tag only when the user explicitly requests that action. A request to commit, push a branch, or publish an APK does not authorize tagging.

Use concise Conventional Commit subjects with a Chinese summary (`feat:完善节目收藏`). Keep each commit focused and describe affected modules and verification in PRs. Ignore build output, IDE caches and local.properties. By the user's explicit instruction, the local APK signing file `signing/sign` and APK_SIGN_* settings in gradle.properties are included as project files and must not be added to ignore rules. Do not print signing passwords in logs or reports. Debug uses .debug application ID suffix; release uses the base ID. APK filenames use app name, build type, version and timestamp.

Refresh on foreground entry and manual action only. Do not schedule periodic refresh. Do not add compatibility paths solely to migrate data or jobs from earlier app versions. Proxy settings apply to playlist and Media3 networking; never send proxy credentials as origin Authorization headers or log secrets.
