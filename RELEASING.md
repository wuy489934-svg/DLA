# Releasing

## Current release

DLA **1.4.1** is the latest published open-source release:

- Repository: https://github.com/wuy489934-svg/DLA
- Release: https://github.com/wuy489934-svg/DLA/releases/tag/v1.4.1
- Tag: `v1.4.1`
- APK + `SHA256SUMS` are attached to the Release.

**2.0 is prepared in this tree but not published yet**: `versionCode 35` /
`versionName 2.0`, user-facing notes in [`docs/release-notes-2.0.md`](docs/release-notes-2.0.md),
summary in `CHANGELOG.md`. Nothing is pushed or released as part of that preparation.

## Build locally

```bash
cd web && pnpm install && pnpm build
cd ../android && ./gradlew assembleDebug
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

## GitHub Actions

- `.github/workflows/ci.yml` runs web typecheck/build and an Android debug build.
- `.github/workflows/release.yml` runs on `v*` tags and creates the signed APK + `SHA256SUMS` Release.
- Signing is read from repository secrets:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`

## Publishing a follow-up release

1. Update `versionCode` / `versionName` in `android/app/build.gradle`.
2. Update the web `package.json` version and `README.md` if the feature set changed.
3. Update `CHANGELOG.md` and `docs/release-notes-<version>.md`.
4. Build locally (`./gradlew assembleRelease`) and record the APK
   `package / versionCode / versionName` plus its SHA-256.
5. Commit and push `main`; confirm CI is green.
6. Create and push a tag:

```bash
git tag -a v2.0 -m "DLA 2.0"
git push origin v2.0
```

7. Wait for the `release` workflow and verify the APK signature and SHA-256.

## After publishing

- Keep the signing key offline; losing it prevents updates.
- Monitor GitHub security advisories and private reports.
- Do not rewrite published history to remove a leaked secret; rotate it.
- Do not publish to Google Play for 1.x: `QUERY_ALL_PACKAGES`, Accessibility, and Shizuku shell make policy review unlikely.
