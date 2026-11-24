# Releasing

## Current release

DLA **3.1.2** is the latest published open-source release:

- Repository: https://github.com/wuy489934-svg/DLA
- Release: https://github.com/wuy489934-svg/DLA/releases/tag/v3.1.2
- Tag: `v3.1.2`（`versionCode 44` / `versionName 3.1.2`）
- APK package: `com.example.chatdemo`（与 v3.0 相同，可直接覆盖升级）
- APK + `SHA256SUMS` are attached to the Release, signed with the release key
  (`f5111725…426c4`).
- User-facing notes: [`docs/release-notes-3.1.2.md`](docs/release-notes-3.1.2.md)；版本摘要见 `CHANGELOG.md`。

Previous releases: `v3.0`（3.0）、`v2.1`（2.1）、`v2.0`（2.0）、`v1.4.1`（1.4.1）、`v1.3.0`（1.3.0）。

## Build locally

```bash
# 1. bundled native runtimes (Node / ripgrep / libc++), not committed
bash scripts/fetch-native.sh

# 2. build (Chaquopy needs Python 3.12 on the build machine)
cd android
./gradlew assembleDebug        # debug APK, no signing key needed
./gradlew assembleRelease      # needs ../keystore/keystore.properties
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

The version is read from `android/app/build.gradle` (`dlaVersionCode` /
`dlaVersionName` defaults) or can be overridden per build with `-PdlaVersionCode=…`
`-PdlaVersionName=…`. **The defaults are what CI uses**, so bump them when releasing.

## GitHub Actions

- `.github/workflows/ci.yml` runs an Android debug build on every push / PR.
- `.github/workflows/release.yml` runs on `v*` tags and creates the signed APK +
  `SHA256SUMS` Release.
- Signing is read from repository secrets:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`

## Publishing a follow-up release

1. Update `dlaVersionCode` / `dlaVersionName` defaults in `android/app/build.gradle`
   (tag `v3.1.2` → code 44).
2. Update `CHANGELOG.md`, `docs/release-notes-<version>.md`, the version badge and
   feature list in `README.md`.
3. Build locally (`./gradlew assembleRelease`) and record the APK `package /
   versionCode / versionName` plus its SHA-256.
4. Commit and push `main`; confirm `ci` is green.
5. Create and push an annotated tag:

```bash
git tag -a v3.1.2 -m "DLA 3.1.2"
git push origin main v3.1.2
```

6. Wait for the `release` workflow; then verify the asset:

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs DLA.apk   # expect f5111725…426c4
aapt2 dump badging DLA.apk | head -1     # expect versionCode 44 / 3.1.2
```

7. Replace the auto-generated English release notes with the Chinese file:

```bash
gh release edit v3.1.2 --notes-file docs/release-notes-3.1.2.md --latest
```

## After publishing

- Keep the signing key offline; losing it prevents updates to the same package.
- Monitor GitHub security advisories and private reports.
- Do not rewrite published history to remove a leaked secret; rotate it.
- Do not publish to Google Play for 1.x: `QUERY_ALL_PACKAGES`, Accessibility and
  Shizuku shell make policy review unlikely.
