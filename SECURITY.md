# Security Policy

## Supported versions

`3.5.x` is the current line. Release candidates may change without notice.

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use GitHub's private vulnerability reporting on the repository, or contact the maintainer listed in `README.md`. Include:

- affected version/commit
- Android version and device
- reproduction steps
- impact assessment
- whether user data or shell/accessibility access is involved

We will acknowledge within 7 days and aim to ship a fix or mitigation within 30 days.

## Threat model

DLA is a power-user tool. It can:

- read the current screen through Accessibility;
- enumerate installed apps (`QUERY_ALL_PACKAGES`);
- execute shell commands through Shizuku (uid 2000), or inside the bundled runtimes
  (Android toybox / CPython / Node) as DLA's own app uid;
- read and write the files it can reach (including `/sdcard`), and render local HTML into
  an off-screen screenshot through `phone_web_shot`;
- send selected conversation content to the API endpoint configured by the user;
- fetch URLs and run web searches on the user's behalf.

It is **not** a security boundary between the user and a malicious model. Prompt injection from web pages, notifications, or documents can attempt to invoke tools. Mitigations:

- approval mode is the default in the public build;
- full-access mode must be explicitly enabled;
- the notification and Agent UI provide an emergency stop;
- shell commands are executed either with the privileges the user granted to
  Shizuku (uid 2000), or as DLA's own uid inside the bundled runtimes (never root);
- every tool result is tagged with the identity that produced it (`[phone_sh · 应用身份]`,
  `[phone_shell · Shizuku uid 2000]`, `[phone_screen · 无障碍]`) and the guide forbids
  attributing one identity's result to another — this reduces misleading conclusions, it
  does not sandbox anything.

Do not enable full-access mode on a device holding data you would not expose to the model or to a malicious prompt.

## Network behavior

- **Model requests** go only to the base URL configured in Settings, with the user's own
  key. There is no account, no telemetry and no crash reporting.
- **`web_fetch`** is a native HTTP client inside the app: desktop UA, gzip, a redirect
  chain capped at 4 requests, 24 000 characters of extracted text and a 3 MB response
  cap. It refuses
  `localhost`, `127.0.0.1`, `0.0.0.0`, `[::1]` and hosts starting with `10.`, `192.168.`
  or `169.254.`, so a prompt cannot trivially use it to probe the local network. This is
  a prefix guard, not a full SSRF filter (other private ranges are not enumerated).
- **`web_search`** depends on the provider. On a DeepSeek endpoint the query goes to the
  official DeepSeek search endpoint (falling back to the free backends if that fails);
  on any other provider it goes directly to free web backends — Baidu, Bing and
  DuckDuckGo, in an order chosen by the query script. That means the search engine learns
  the query and the device's IP. No cookies, keys or conversation history are sent with
  it.
- **The Markdown renderer** (KaTeX / mermaid) is offline: it only loads bundled assets,
  has no JS bridge, no DOM storage and no network access.

## Notifications

- `POST_NOTIFICATIONS` is requested once on Android 13+ (the app remembers that it
  asked). Refusing it only disables notifications.
- The result notification (channel `agent-result-v2`) carries the conclusion preview
  (first ~120 characters), rounds / steps / elapsed time and the last tool **name**, plus
  the error text when a run failed. It does not contain tool parameters or tool results.
  It is posted by `:agent`, not by the UI, so it also works when the UI process is dead.
- Turning off Settings → 通知 → 任务通知 disables these notifications; the ongoing
  foreground-service notification is not affected by that switch and disappears when the
  run ends.

## On-device data

- `files/native-sessions/<id>.json` — conversations, drafts, task state and usage stats.
- `files/agent-runs/<requestId>.json` — per-run journal (text, thinking, tool cards,
  usage, task state) written by `:agent` so that a killed UI process can recover a
  finished answer. It is deleted as soon as the UI claims it; a journal that was never
  claimed (for example, a crash loop) stays until the next successful claim or until app
  data is cleared.
- `files/diagnostics/events.log` — a 256 KB ring log of tool names and stack traces. It
  never contains message bodies.
- `/sdcard/Download/DLA-web-shots/` — screenshots produced by `phone_web_shot`; they are
  ordinary files the user can open or delete.
- **Truncated tool arguments are never applied.** When the model's output limit cuts a
  tool call mid-JSON, the call is marked truncated, not executed, and answered with a
  recoverable hint, so a half-written file is not silently produced.

## Secrets

- API keys (model endpoint and Qwen3-TTS) are user-supplied and stored in the app's
  SharedPreferences **in plain text** — like most local settings, they are not encrypted
  with the Android Keystore. Anyone who can read the app's private data (root, a
  full-device backup, a debugging build) can read them.
- Keys must never be committed, pasted into issues, or included in diagnostics.
- Release signing keys and passwords live outside the repository.
- Diagnostics intentionally exclude message bodies; tool names and stack traces are kept for debugging.
