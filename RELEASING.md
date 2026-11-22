# Releasing

The source tree and a signed RC are prepared, but **nothing is published yet**.

## Recommended timing

Do not publish the same day the RC is built. Suggested gate:

1. Freeze `1.3.0` for 7 days of dogfooding.
2. Run a clean-clone build on a machine without the private workspace
   (`BUILD.md` / CI).
3. Confirm the application id and GitHub owner; change
   `io.github.dla.agent` if needed.
4. Back up the release key and passwords outside the repository; verify the
   key is not in Git history.
5. Install the signed APK on at least two Android versions (14/15/16) and
   exercise: LLM chat, Agent with Shizuku, Agent with accessibility disabled,
   emergency stop, crash recovery.
6. Publish source first, then a GitHub Release with the APK and SHA-256.
7. Tag `v1.3.0` only after the RC has no open P0 issue.

Earliest suggested public date if the RC is built on 2026-11-21:
**2026-11-28**.

## Build a release

```bash
cd web && pnpm install && pnpm build
cd ../android
./gradlew assembleRelease
```

The release build reads `../keystore/keystore.properties`; the `keystore/`
directory and `*.jks` are ignored. Never commit them.

## Publish

- GitHub Releases: APK + `SHA256SUMS` + short notes.
- Do **not** publish to Google Play for 1.0: `QUERY_ALL_PACKAGES`,
  Accessibility, and Shizuku shell make policy review unlikely.
- Obtainium users can consume the GitHub Release directly.
- F-Droid only after reproducible builds and a license/anti-feature review.

## After publishing

- Keep the signing key offline; losing it prevents updates.
- Open a security policy and monitor private reports.
- Do not rewrite published history to remove a leaked secret; rotate it.
















## GitHub channel (prepared, not published)

- Remote: `https://github.com/wuy489934-svg/DLA.git` (confirm the final repo name before pushing).
- CI: `.github/workflows/ci.yml` builds web + debug APK on push/PR.
- Release: `.github/workflows/release.yml` runs on `v*` tags and publishes APK + SHA256; signing is read from repository secrets (see below).
- Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Do not commit the JKS or passwords. The workflow is prepared but no tag is pushed in this pass.

