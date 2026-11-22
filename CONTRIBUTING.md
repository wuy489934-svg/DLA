# Contributing

Thanks for helping. This project touches powerful Android APIs; please keep changes small and explicit.

## Before you start

- Read `SECURITY.md` and `PRIVACY.md`.
- Do not commit API keys, keystores, `local.properties`, personal screenshots, or captured chat data.
- For security issues, use private reporting instead of a public issue.

## Development

```bash
cd web
pnpm install
pnpm typecheck
pnpm lint
pnpm build

cd ../android
./gradlew assembleDebug
```

Android unit tests are not yet comprehensive. If you change the WebView bridge or Agent tools, include a manual test note (device, Android version, Shizuku/accessibility state).

## Pull requests

- Keep the change focused; describe the user-visible effect.
- Update `CHANGELOG.md` for user-facing changes.
- Add/update tests where practical.
- Do not reformat unrelated files.
- By contributing, you agree to license your contribution under Apache-2.0 and to the Developer Certificate of Origin (sign off commits with `git commit -s`).

## Commit style

Conventional prefixes are welcome: `feat:`, `fix:`, `docs:`, `build:`, `security:`.

## Code of conduct

Be respectful. Harassment, doxxing, and publishing another person's private data are not tolerated.
