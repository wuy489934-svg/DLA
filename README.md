# DLA

DLA = **DeepSeek LLM Agent**. Both LLM and Agent modes use the DeepSeek-compatible endpoint; DLA no longer exposes other model providers.

**DeepSeek LLM Agent** — an unofficial Android client for DeepSeek-compatible chat APIs, with an optional on-device automation mode powered by Shizuku and Android Accessibility.

> This is not an official DeepSeek product and is not affiliated with DeepSeek. “DeepSeek” is used only to describe API compatibility.

DLA is an independent native Android agent. It is **not** a repackaged official demo, and it is not a clone of another Android agent UI. The Web UI is built on an MIT-licensed upstream, but the native engine, tools, scheduler, iteration tree, diagnostics, and evals are DLA-specific. See `docs/POSITIONING.md`.

## Status

`1.3.0` — release candidate. Source is prepared; no public release has been published yet. See `CHANGELOG.md` and `RELEASING.md`.

The application id `io.github.dla.agent` is a placeholder for the eventual GitHub owner. Change it before the first public release if the project does not live under that namespace. The Java namespace `com.example.chatdemo` is retained for upgrade/data compatibility; it is not the product identity.

## Features

- Chat UI (React + WebView) with streaming, Markdown, reasoning, attachments.
- Agent mode: native Android foreground service, DeepSeek tool calls, MCP servers, approval flow.
- Android tools: `phone_screen`, tap/swipe/input, screenshots, app/file/search helpers.
- In-process accessibility screen reader (`ChatDemoAccessibilityService`) for low-latency `phone_screen`; falls back to `uiautomator`.
- Shizuku shell backend for advanced device operations.
- Optional full-access mode (off by default in this public build).
- Emergency stop from the notification or the Agent UI.
- Local diagnostics and one-tap ZIP export.

## Requirements

- Android 9+ (API 29+), target/compile SDK 36.
- Your own API key for a DeepSeek-compatible endpoint.
- **Shizuku** installed and running for shell tools.
- **Accessibility service** enabled for fast screen reading (optional but recommended).
- A PC is only needed for building; the app runs entirely on the phone.

## Install

Download the APK from GitHub Releases (once published) and install it. Because the app requests powerful permissions, Android will warn you. Read `SECURITY.md` first.

## Build

See `BUILD.md`.

```bash
cd web
pnpm install
pnpm build

cd ../android
./gradlew assembleDebug
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Security and privacy

This app can read the screen, enumerate installed apps, and execute shell commands through Shizuku. Treat it as a developer/power-user tool, not a sandbox.

- Public defaults: full-access mode **off**, approval required.
- No telemetry. API requests go directly to the endpoint you configure.
- Chat history is stored in WebView IndexedDB, with a local JSON mirror under the app's private files.
- Diagnostics contain event names and stack traces, not message bodies.
- Never paste API keys into issues.

See `SECURITY.md` and `PRIVACY.md`.

## Project layout

```text
web/       React chat UI (fork of deepseek-ai/chat-demo, MIT)
android/   Android WebView shell, Agent service and tools
docs/      Architecture notes
licenses/  Third-party license texts
```

## Credits

- Web UI based on [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo), MIT.
- App icon: [Lucide](https://lucide.dev) `bot` icon, ISC. See `NOTICE`.
- Shizuku by RikkaApps, Apache-2.0.
- All other dependencies retain their own licenses; see `THIRD_PARTY_NOTICES.md`.

## License

Apache License 2.0 for this project's original code. The `web/` directory also contains MIT-licensed upstream code; see `licenses/chat-demo-MIT.txt` and `web/LICENSE`.

















