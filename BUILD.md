# Building DLA

## Prerequisites

- JDK 21 (CI uses Temurin 21; the app compiles with `jvmTarget 17`)
- Android SDK Platform 36, Build Tools 35+
- Python 3.12 on the build machine (Chaquopy compiles the stdlib to pyc)
- `curl` + `tar` (the native runtimes are fetched by `scripts/fetch-native.sh`)
- Gradle 8.13 via the wrapper (`android/gradlew`); Android Gradle Plugin 8.9.2, Kotlin
  2.1.0, Compose BOM 2024.09.03 and Chaquopy 17.2.0 are resolved by Gradle automatically.
- `android/app/src/main/assets/renderer/` (KaTeX 0.16.11 + mermaid 10.9.1) is committed to the repo, so a build does not need network access for it.

## 1. Fetch the bundled runtimes

```bash
bash scripts/fetch-native.sh    # libnode / libdshnode / librg / libc++ → android/app/src/main/jniLibs/arm64-v8a
```

These binaries are not in Git (about 91 MB). CPython comes from Chaquopy at build time.

## 2. Build Android

```bash
cd android
./gradlew assembleDebug
```

For a release build, provide a signing key outside the repository:

```bash
cd android
cat > ../keystore/keystore.properties <<'EOF'
storeFile=../keystore/dla-release.jks
storePassword=...
keyAlias=dla
keyPassword=...
EOF
./gradlew assembleRelease
```

`keystore/` is ignored. Never commit a JKS or its passwords.

Release builds are minified (R8) and shrink resources; debug builds are not.

## Version and build parameters

Everything is a Gradle property; the defaults in `android/app/build.gradle` are what CI
publishes, so keep them in sync with the release:

| Property | Default | Meaning |
|---|---|---|
| `-PdlaVersionCode` | `52` | `versionCode` |
| `-PdlaVersionName` | `3.6.0` | `versionName` |
| `-PappId` | `com.example.chatdemo` | `applicationId` |
| `-PdlaPythonVersion` | `3.12` | Chaquopy Python version; must match `chaquopy.version` major.minor |
| `-PdlaBuildPython` | unset (or `DLA_BUILD_PYTHON`) | build-machine interpreter used to compile pyc |
| `-PperfSign=true` | `false` | sign the release build with `~/.android/debug.keystore` so it can overwrite an already-installed debug build |

Examples:

```bash
# a local release with an explicit Python and a different package id
./gradlew assembleRelease \
  -PdlaBuildPython=/path/to/python3.12 \
  -PappId=com.example.chatdemo.local \
  -PdlaVersionCode=99 -PdlaVersionName=3.6.0-local

# a release build signed with the local Android debug key
./gradlew assembleRelease -PperfSign=true
```

`-PperfSign=true` requires `~/.android/debug.keystore` to exist; without it the property
is ignored and the release signing config is used (or the APK stays unsigned when
`keystore/keystore.properties` is absent).

## Local vs CI

| | Local | GitHub Actions |
|---|---|---|
| OS / JDK | Windows or Linux, JDK 21 | `ubuntu-latest`, Temurin 21 |
| SDK | Android SDK 36, Build Tools 35+ | installed by the workflow (`platforms;android-36`, `build-tools;35.0.0`) |
| Native runtimes | `scripts/fetch-native.sh` once | downloaded on every run |
| Python | whatever `-PdlaBuildPython` points at (must be 3.12) | 3.12 preinstalled on the runner |
| Flags | any `-P…` you like | **none** — `assembleDebug` for `ci`, `assembleRelease` for a `v*` tag |
| Signing | `keystore/keystore.properties` or `-PperfSign=true` | the four `ANDROID_KEYSTORE_*` secrets |
| Output | local `app-release.apk` / `app-debug.apk` | `DLA.apk` + `SHA256SUMS` attached to the Release |

## Windows notes

The project path must not contain characters that break the Android toolchain: prefer a
short ASCII path without non-ASCII characters or spaces. The repository itself is
path-agnostic; if the workspace path contains non-ASCII characters, a junction for the
SDK may be needed.

## Outputs

- Debug: `android/app/build/outputs/apk/debug/app-debug.apk`
- Release: `android/app/build/outputs/apk/release/app-release.apk`

## Verification

```bash
# package id / version
aapt2 dump badging app-release.apk | head

# signature
apksigner verify --print-certs app-release.apk
```

Expected for a published build: `com.example.chatdemo`, `versionCode 52`, `versionName 3.6.0`, signed with the release key.
