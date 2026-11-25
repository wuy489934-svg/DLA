# Releasing

## Current release

DLA **3.6.0** is the latest published open-source release:

- Repository: https://github.com/wuy489934-svg/DLA
- Release: https://github.com/wuy489934-svg/DLA/releases/tag/v3.6.0
- Tag: `v3.6.0`（`versionCode 52` / `versionName 3.6.0`）
- APK package: `com.example.chatdemo`（与 v3.5.0 / v3.1.2 / v3.0 相同，可直接覆盖升级）
- APK + `SHA256SUMS` are attached to the Release, built by CI from the tag and signed
  with the release key (`f5111725…426c4`).
- User-facing notes: [`docs/release-notes-3.6.0.md`](docs/release-notes-3.6.0.md)；
  版本摘要见 `CHANGELOG.md`。

Previous releases: `v3.5.0`（3.5.0）、`v3.1.2`（3.1.2）、`v3.0`（3.0）、`v2.1`（2.1）、
`v2.0`（2.0）、`v1.4.1`（1.4.1）、`v1.3.0`（1.3.0）。

## Build locally

```bash
# 1. bundled native runtimes (Node / ripgrep / libc++), not committed
bash scripts/fetch-native.sh

# 2. build (Chaquopy needs Python 3.12 on the build machine)
cd android
./gradlew assembleDebug        # debug APK, no signing key needed
./gradlew assembleRelease      # needs ../keystore/keystore.properties
```

Pass the Python interpreter explicitly when 3.12 is not the one on `PATH`:

```bash
./gradlew assembleRelease -PdlaBuildPython=/path/to/python3.12
```

For a signed release, provide the signing key outside the repository:

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

`keystore/` and `*.jks` are ignored. Never commit a JKS or its passwords.

## Version and build parameters

`android/app/build.gradle` holds the defaults. **CI passes no `-P` flags**, so the defaults
are exactly what gets published — they must be bumped for every release:

| Property | Default | Meaning |
|---|---|---|
| `dlaVersionCode` | `52` | published `versionCode` |
| `dlaVersionName` | `3.6.0` | published `versionName` |
| `appId` | `com.example.chatdemo` | published package id (the workflow does not override it) |
| `dlaPythonVersion` | `3.12` | Chaquopy Python version |
| `dlaBuildPython` | unset (or `DLA_BUILD_PYTHON`) | build-machine interpreter used to compile the stdlib to pyc |
| `perfSign` | `false` | `true` signs the release build with `~/.android/debug.keystore` instead of the release key |

Overrides for local builds: `-PdlaVersionCode=…`, `-PdlaVersionName=…`, `-PappId=…`,
`-PdlaBuildPython=…`, `-PperfSign=true`. Release builds are minified (R8) and shrink
resources; debug builds are not.

## GitHub Actions

- `.github/workflows/ci.yml` runs a debug build on every push to `main` / `master` and on
  pull requests.
- `.github/workflows/release.yml` runs on `v*` tags (and can be started manually): it
  fetches the native runtimes, builds `assembleRelease`, uploads `DLA.apk` +
  `SHA256SUMS` and creates the Release.
- Signing is read from repository secrets:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`

## Publishing a follow-up release

1. Update `dlaVersionCode` / `dlaVersionName` defaults in `android/app/build.gradle`
   (for `v3.6.0` → `52` / `3.6.0`).
2. Update `CHANGELOG.md`, `docs/release-notes-<version>.md`, the version badge and the
   feature list in `README.md`.
3. Build locally (`./gradlew assembleRelease`) and record the APK `package /
   versionCode / versionName` plus its SHA-256.
4. Commit and push `main`; confirm `ci` is green.
5. Create and push an annotated tag:

```bash
git tag -a v3.6.0 -m "DLA 3.6.0"
git push origin main v3.6.0
```

6. Wait for the `release` workflow; then verify the asset:

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs DLA.apk   # expect f5111725…426c4
aapt2 dump badging DLA.apk | head -1     # expect versionCode 52 / 3.6.0
```

7. Replace the auto-generated English release notes with the Chinese file:

```bash
gh release edit v3.6.0 --notes-file docs/release-notes-3.6.0.md --latest
```

## After publishing

- Keep the signing key offline; losing it prevents updates to the same package.
- Monitor GitHub security advisories and private reports.
- Do not rewrite published history to remove a leaked secret; rotate it.
- Do not publish to Google Play: `QUERY_ALL_PACKAGES`, Accessibility and Shizuku shell
  make policy review unlikely.
