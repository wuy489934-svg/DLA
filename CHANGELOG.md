# Changelog

## 2.0

- **Native Jetpack Compose UI** replaces the WebView as the default surface:
  session list, chat, streaming, approval cards, iteration tree and settings are
  all native. The web UI is kept as a compatibility shell and can still be opened
  from Settings → Interface.
- **The Agent now runs in its own `:agent` process.** `AgentService` hosts the run
  behind a foreground service; the UI talks to it through `AgentClient`
  (`Messenger`), reattaches by event cursor after a crash or recreate, and keeps
  long tasks alive when the UI process is gone.
- **Stopping a run kills the Termux process tree it started.** Every `run()` gets
  a `runId` and records the command's pid; cancellation kills that pid, its direct
  children and any process still carrying the run marker, then KILLs after two
  seconds, so npm, python, downloads and file writes do not keep running.
- **Termux wrapper rewritten to stop hanging.** The wrapper no longer uses
  `set -m` job control or fd redirection (both hang under Termux's shell, which
  looked like “Termux never returns a result”); it now backgrounds the command,
  `wait`s for it and writes the pid to a run file.
- **Offline math and diagrams**: KaTeX 0.16.11 and mermaid 10.9.1 are bundled
  under `assets/renderer/` and served to an isolated WebView that cannot reach
  the network; ordinary Markdown renders natively, so chat rows no longer jump
  when the list recycles them.
- **Bilingual UI** (Chinese / English, follow-system by default) for the native
  surface, including errors and approval prompts.
- **Attachments** in the native composer: images (compressed), PDF (rendered page
  by page), docx, and plain text / code files. Several files can be queued at
  once and are shown as removable chips before sending.
- **Folder batch import** (fallback path): when *All files access* is not
  granted, the permission card still offers the SAF folder picker; the app walks
  the granted tree recursively (max 24 files, depth 3, 8 MB per file), copies the
  files into `agent-uploads` and attaches them with real paths, so Termux tools
  can `cat`/`pdftotext`/`unzip` exactly what was imported. The granted tree URI is
  stored in the `folderUri` preference.
- **On-device file browser (📂)**: the attach slot opens a built-in browser
  instead of the system picker — it starts in `Pictures`, offers quick jumps
  (pictures / camera / downloads / documents / storage root), a breadcrumb with
  *up*, a **search box that walks the current folder recursively** (depth 6, 300
  hits, hidden dirs and `Android/` skipped), multi-select, and “import this
  folder” in Agent sessions. It needs *All files access*
  (`MANAGE_EXTERNAL_STORAGE`, falling back to `READ_EXTERNAL_STORAGE` on Android
  10 and below); without it the app shows a one-tap permission card, so the
  Android 11+ “for your privacy, choose another folder” screen never appears in
  the normal path. The system picker remains only as a fallback entry.
- **Search inside attachments**: files are matched while typing, and results show
  the folder they live in, so a document can be found without drilling down.
- **No voice input**: the microphone entry was removed from the native composer
  and from the web shell, and `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS` are no
  longer requested.
- **Launcher icons rebuilt to the adaptive safe area**: mascot art is scaled to
  50% of the 108dp canvas (the visible square is 72dp), so the chibi and the Cue
  robot are fully visible under any shape mask instead of bleeding into it.
- **Iteration tree**: rounds, parallel groups, cached results, statuses and
  elapsed time, with click-to-jump to the matching message.
- **Themes and feedback**: dark / light / follow-system for the native UI, plus
  optional UI sounds.
- **Mascot motion**: the home hero draws the v2 “Ming” beacon with layered glow
  (breathing, drifting, halo), and the thinking card shows the v1 “Cue” avatar
  with a pulsing title. Animations only touch `graphicsLayer` and the draw pass,
  so they never re-measure a list row.
- **Attachments reach the Agent too**: files picked for an Agent task are copied
  into app storage and passed with a local path, so tools can read the same file
  the composer sent.
- Mirror mode is kept as code only: the public build ships **no** persona text or
  portrait, and the settings page shows “no built-in persona” when the assets are
  absent. No persona assets, API keys, keystores or local paths are included.
- New original **v1 Cue** launcher icon (square, round, adaptive foreground on a
  `#0A1312` plate, monochrome) generated from `docs/mascot/v1/`; the in-app
  mascot art (`res/drawable-nodpi/mascot_cue.png`, `mascot_moon.png`) comes from
  the same artwork, and all three proposals stay under `docs/mascot/`.
- **Fixed: “Termux test” failed while the Agent worked.** `TermuxResultService`
  runs in the `:agent` process, so a `TermuxBridge.run()` issued from the UI
  process (Settings → Advanced → Termux environment → *Test*) could never be
  completed by its in-process future and always timed out with “Termux did not
  return a result”. The result service now also broadcasts the result inside the
  app, and the caller registers a runtime receiver, so both processes see it and
  the test returns `DLA_TERMUX_OK` in about a second.
- VersionCode 36 / versionName 2.0.

## 1.4.1

- Added optional **Termux** execution backend: a single `termux` tool
  (`action=status/capabilities/run`) bridged over `com.termux.RUN_COMMAND`, with
  stdout/stderr/exit code returned through a `PendingIntent` result service.
- **Shizuku or Termux, either one is enough**: the Agent refuses to start only
  when both are unavailable, and the UI explains both setup paths.
- **Tools are discovered on demand**: only 11 core device actions are injected
  up front; `phone_shell`, `termux`, file/settings/zip/docx helpers must be found
  with `tool_search` first. The injected tool set is trimmed by availability
  (no Shizuku -> no `phone_*`; no Termux -> no `termux`).
- Agent system prompt updated for the on-demand tool model and self-awareness
  (unofficial product, no fabricated operations, confirm risky actions).
- New original launcher icon (square + round + adaptive + monochrome) and a
  demo GIF under `docs/assets/`.
- New documentation: `docs/AGENT-RUNTIME.md` (full execution path) and
  `docs/INSTALL.md` (detailed Android install).
- VersionCode 23 / versionName 1.4.1.

## 1.3.0

- DLA is now DeepSeek-only again. Removed the OpenAI/Qwen/Anthropic provider
  selector and Agent protocol switch; other models were not a good fit for DLA.
- Kept DeepSeek model Base URL/model fields and “fetch models & test connection”.
- Key verification and model tests use a native Java HTTP bridge, bypassing
  WebView CORS; API keys are verified with a minimal request.
- Base URL/model are blank by default and resolved internally when blank.
- Removed test-key residue, personal device identifiers, and unused persona
  material from the public tree.
- VersionCode 19 / versionName 1.3.0.

## 1.1.1

- Manual context compaction is now disabled at or below 20% context usage and
  the button reads “无需压缩 / No need” with an explanatory tooltip.
- VersionCode 10 / versionName 1.1.1.

## 1.1.0

- Added lightweight view/modal/drawer transitions using transform/opacity only;
  respects `prefers-reduced-motion`.
- Persisted task plan/current/notes in the Agent checkpoint and restored them
  when a run is resumed.
- Prunes oversized tool results before context compaction (8K budget, head/tail
  retention); original history is unchanged.
- Added TTFT (first-token latency) to the native usage event and Agent stats bar.
- Compacted Agent stats labels to fit narrow phones.
- Welcome orb label is now DLA.
- VersionCode 9 / versionName 1.1.0.

## 1.0.6

- Rebranded the sidebar to DLA / Device Local Agent; removed the “API usage
  demo” identity.
- Added `docs/POSITIONING.md`: DLA is not a repackaged official demo and not a
  clone of another Android agent UI.
- Removed Yutu comparison wording from the engine; updated native test/demo
  comments and MCP client identity to DLA.
- Web UI README now states the upstream MIT base and DLA-specific work.
- VersionCode 8 / versionName 1.0.6.

## 1.0.5

- Added parallel tool scheduling for independent read-only tools (max 3),
  preserving tool_result order and adding round/parallelGroup/cached fields.
- Added per-run tool result caching and cache invalidation on actions.
- Added Agent iteration tree panel in the chat UI: rounds, parallel groups,
  statuses, elapsed time, plan/note nodes, click-to-highlight.
- Added the `evals/` harness (12 read-only device cases, JSON + Markdown).
- Native-first tool layer retained; shell remains only for privileged/fallback.
- VersionCode 7 / versionName 1.0.5.

## 1.0.4

- Native-first tool layer: accessibility gestures for tap/swipe/click-text,
  ACTION_SET_TEXT for input, global actions for back/home/recents/notifications,
  PackageManager launch, direct device/settings/clipboard reads, native
  screenshot with `canTakeScreenshot`, and native zip/docx handling with shell
  fallback only where app permissions require it.
- Shell is now reserved for force-stop, secure settings writes, notifications,
  broad shared-storage grep, and the explicit escape hatch.
- Shell test shows persistent ✅/❌ result text.
- Accessibility cache is invalidated after UI actions.
- VersionCode 6 / versionName 1.0.4.

## 1.0.3

- Shell test now returns explicit success/error text and shows a persistent
  result panel in Agent settings.
- Added screen-cache invalidation after tap/swipe/input/key/open/stop actions.
- Added `phone_tap_text` and `phone_wait`; Agent prompt now prefers one-shot
  text taps, explicit waits, and task_plan/task_note for multi-step work.
- VersionCode 5 / versionName 1.0.3.

## 1.0.2

- Usage stats are now per-session. New conversations start at 0; Agent shows the
  full bar, while LLM shows only context usage and the compact button.
- Agent mode is blocked when Shizuku is not running/authorized, with a warning
  and a direct path to Agent settings.
- Shizuku authorization persists; accessibility stays enabled. The UI explains
  the non-root reboot limitation and root boot-start option.
- Added `phone_tap_text` (find text/description and tap in one call) and
  `phone_wait` (wait for UI/until text), with prompt rules to avoid repeated
  screen dumps and multi-step tap sequences.
- VersionCode 4 / versionName 1.0.2.

## 1.0.1

- Rounds now count one user + AI exchange as one round; historical API-round
  counts are reset, while token/step counters are preserved.
- Context window changed to 1,000,000 tokens; context usage below 10% is shown
  with one decimal (e.g. 0.4%).
- Shizuku install now tries domestic GitHub accelerators first
  (`gh-proxy.com`, `ghfast.top`, `ghproxy.net`) before direct GitHub; failed
  downloads fall back to the official download page.
- Agent settings show the installed Shizuku version after installation.
- VersionCode 3 / versionName 1.0.1.

## 1.0.0 — formal private beta

- Added a chat footer with rounds/steps, tokens/s, cumulative tokens, cache
  hit rate, context usage, and a quality-first context-compaction button.
- Manual compaction summarizes older turns while preserving the last 4 turns
  verbatim; automatic compaction triggers at 65% after a reply. The visible
  chat history is never deleted.
- Added a one-tap Shizuku install/download entry in Settings → Agent.
- Enter in the composer now inserts a newline; sending is button-only.
- Agent usage events now include cache tokens and elapsed time for tok/s.
- VersionCode 2 / versionName 1.0.0; targetSdk 36.

## 1.0.0-rc1 — public release candidate

First open-source candidate. Based on the private 0.9.5-rc1 build, with the
following distribution changes:

- New application id `io.github.dla.agent`; new independent release signing key.
- New Lucide `bot` launcher icon (ISC), replacing the non-commercial character
  artwork used by the private build.
- Removed the bundled “mirror mode” persona and portrait; persona images are
  user-supplied only.
- Added Apache-2.0 project license and third-party notices; upstream web UI
  remains MIT.
- Added README, BUILD, SECURITY, PRIVACY, CONTRIBUTING, CODE_OF_CONDUCT.
- Removed personal screenshots, keystores, reverse-engineered APKs, and build
  artifacts from the source tree.

Engineering changes inherited from 0.9.5-rc1:

- `onRenderProcessGone` on all WebViews; renderer crash no longer kills the app.
- Host-side session JSON mirror for crash recovery.
- In-process `ChatDemoAccessibilityService` for `phone_screen`, with
  `uiautomator` fallback and `source: fallback` marker.
- `SystemClock.elapsedRealtime()` for timeouts/elapsed time.
- Local diagnostics log and ZIP export.
- targetSdk/compileSdk 36.
- Notification + UI emergency stop.
- Shizuku status/authorization entry.
