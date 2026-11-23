# Building DLA

## Prerequisites

- JDK 17+ (JDK 21 tested)
- Android SDK Platform 36, Build Tools 35+
- Python 3.12 on the build machine (Chaquopy compiles the stdlib to pyc)
- `curl` + `tar` (the native runtimes are fetched by `scripts/fetch-native.sh`)
- Gradle is provided by the wrapper (`android/gradlew`)
- Kotlin 2.1.0, the Compose compiler plugin and Compose BOM 2024.09.03 are resolved by Gradle automatically (jvmTarget 17); no separate install.
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

The Gradle task `syncWebAssets` copies `web/dist` into
`android/app/src/main/assets/public` before packaging.

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

## Windows notes

The project path must not contain characters that break the Android toolchain. Prefer a short ASCII path such as `C:\src\dla`. The repository itself is path-agnostic; the old private build used a non-ASCII workspace and required a junction for the SDK.

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
