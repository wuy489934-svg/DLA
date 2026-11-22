# Changelog

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
