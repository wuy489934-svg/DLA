# Changelog

> **Termux note / Termux 状态（先读这条）**：Termux support was **removed entirely in 2.2**
> and is **not** an optional backend — there is no `termux` tool, no RUN_COMMAND, no
> Termux panel. The 3.5.0 and 3.6.0 releases do not touch Termux. Entries below that
> mention Termux (1.4.x / 2.x) are kept for history only.
>
> **Termux 集成已在 2.2 整体删除，不是可选后端**；3.5.0 / 3.6.0 的更新与 Termux 无关。
> 下文 1.4.x / 2.x 的 Termux 条目只是历史记录。

## 3.6.0

- **Thinking stays on for the whole task.** The old watchdog set a run-wide
  `forceNoThinking` flag on the first overrun, so long tasks turned into tool-only
  execution after round 23. Now `suppressThinking` applies to the current round's retry
  only: every round starts with thinking enabled again. The truncated reasoning tail
  (≤600 characters) is fed into the retry prompt so the model does not restart from
  scratch, same-round retries do not consume a round, and repeated overruns tighten the
  per-round budget 12K → 6K → 3K characters instead of switching thinking off for good.
  A `max_tokens` round that produced nothing but thinking follows the same recovery path.
- **Thinking blocks are passed back (Anthropic / DeepSeek requirement).** With thinking
  enabled, assistant messages must carry the thinking block and its signature, otherwise
  the next tool round fails with `The content[].thinking in the thinking mode must be
  passed back to the API`. The SSE parser now accumulates `thinking` and
  `signature_delta`, `assistantMessage()` puts the block first in `content`, and a
  watchdog-truncated round re-attaches the abandoned thinking before the retry. The
  block is only sent on the Anthropic dialect — OpenAI-compatible layers reject it.
- **One collapsible Process card.** Every “round N” (thinking + that round's tools) used
  to be its own card, which pushed long answers far down the screen. All rounds now live
  in a single card below the iteration tree: expanded while the run streams, collapsed
  once the message is stored, with a header showing rounds / steps / elapsed. The tree and
  the card now count rounds the same way (a thinking-only round counts).
- **Status bar reflow.** Cache hit rate and the current turn's elapsed time moved to their
  own 12 sp line above the context bar instead of sharing one line with the ten dashed
  segments.
- **No Termux changes.** This release is about the agent loop and the UI; the Termux
  integration is still gone (see the note at the top).

## 3.5.0

- **One loop, two wire protocols.** The LLM and Agent areas can talk either Anthropic
  Messages (DeepSeek `/anthropic/v1`, Claude) or OpenAI Chat Completions
  (`/v1/chat/completions`: GPT, Qwen, GLM, Kimi, OpenRouter, Ollama …); a switch in
  Settings picks the dialect and moves the base URL between the two paths. Messages keep
  the internal block shape and `OpenAiWire` lowers them to `role:tool` / `tool_calls` /
  `image_url` right before the request, so adding a vendor no longer means touching the
  agent loop.
- **A provider adapter instead of per-vendor forks.** `ProviderCompat` ships 12 one-tap
  presets (DeepSeek, DeepSeek over OpenAI, Qwen / Bailian, Kimi, GLM, OpenAI, Claude,
  OpenRouter, SiliconFlow, Groq, Ollama, custom), detects the vendor from the base URL
  or the model name, and applies the quirks that actually differ: `max_tokens` vs
  `max_completion_tokens`, when `stream_options` may be sent, omitting `temperature` for
  o-series / gpt-5, the `thinking` / `enable_thinking` / `reasoning_effort` switch, and
  server-side web tools only on the DeepSeek endpoint. Non-DeepSeek endpoints get a
  neutral identity prompt instead of claiming to be DeepSeek.
- **Pick the model you just fetched.** “Fetch and test connection” now keeps the returned
  list; Settings shows “pick one of N models” with a search box, and the list is cached
  so it can be reused without fetching again.
- **A finished run is never lost with the UI process.** `:agent` journals each run to
  `files/agent-runs/<requestId>.json` (text, thinking, tool cards, usage, task state;
  writes throttled to one per 700 ms) and broadcasts when it reaches a terminal state.
  If the UI was killed, the next launch claims the journal and restores the answer —
  tool cards included — and assistant messages carry a `runId`, so a live event and a
  recovered journal cannot write the same answer twice.
- **Output limits became recoverable instead of fatal.** A tool call whose arguments
  were cut by `max_tokens` is marked truncated, not executed, and answered with a hint
  that teaches `phone_write` + `phone_append` in ≤3000-character chunks while the round
  continues. A reply that ends on `max_tokens` with no tool call is appended as an
  assistant message plus a “continue from where you stopped” nudge, up to 3 times, with
  a status event each time.
- **`phone_web_shot`: look at the page before shipping it.** The agent can render a local
  HTML file off-screen inside DLA's own window and get the screenshot back as an image
  block, so it can check its own CSS / layout / canvas work and fix what it sees.
  Rendering happens in the UI process (a WebView with no window draws black) through the
  `WebShotReceiver` bridge; shots land in `/sdcard/Download/DLA-web-shots/`, and the tool
  refuses with a clear error when DLA is not in the foreground.
- **Web tools no longer depend on the vendor.** `web_fetch` is a native HTTP fetcher
  (desktop UA, gzip, redirects capped at 4 requests, 24 000 characters and 3 MB caps,
  a localhost / private-prefix guard), so it works with any provider. `web_search` keeps the official
  DeepSeek endpoint when the provider is DeepSeek and otherwise walks free HTML
  backends — Chinese queries: Baidu → Bing CN → Bing → Bing RSS → DuckDuckGo; English:
  Bing → Bing CN → Baidu → RSS → DDG — and reports diagnostics (status, page size,
  whether `b_algo` was present) instead of a bare “search failed”.
- **Web pages default to something that opens.** The guide asks for a single
  self-contained HTML file (CSS/JS inline, no CDN, no exotic features) and for opening
  instructions beyond a bare `file://` link, because desktop browsers cannot see phone
  paths and mobile browsers often block the scheme.
- **Every tool result says who ran it.** Results are prefixed with their source —
  `[phone_sh · 应用身份]` (app identity), `[phone_shell · Shizuku uid 2000]`,
  `[phone_screen · 无障碍]` (accessibility) — and the guide forbids attributing one
  identity's success to another. `phone_shell` is injected into the core list as soon as
  Shizuku is ready, so a capability the runtime block lists as available is visible
  without a `tool_search` round.
- **Feedback you can see and hear.** A tap throws seven small blocks (2.5–7 dp, 430 ms,
  at most 3 bursts at once) from the touch point without consuming the gesture. Sounds
  moved to their own `HandlerThread` (the first `ToneGenerator` construction used to
  hitch the main thread) and are warmed up at startup. Task completion plays a synthetic
  880 → 1318 Hz WAV (~0.35 s) through `MediaPlayer` on the media stream, audible while
  the app is in the foreground; failures play a falling 523 → 330 Hz pair.
- **Task notifications with a way back.** Finishing, failing or stopping a run posts on
  the `agent-result-v2` channel (high importance, default notification sound, vibration):
  the title follows the outcome, the body carries the conclusion preview plus
  rounds / steps / elapsed / last tool, and tapping opens that conversation. The ongoing
  foreground-service notification now exists only while there is work and is removed with
  `stopForeground` when the run ends.
- **Navigation is a real page stack.** The current screen is a `List<Screen>` saved with
  `listSaver`, so back pops one level: conversation → Settings → back returns to the
  conversation, and opening Settings no longer closes it. Relaunching the app returns to
  the last base screen.

## 3.1.6

- Added the Anthropic / OpenAI protocol split, `OpenAiWire`, the `ProviderCompat` adapter
  with its presets and detection, the cached model list and picker, and the run journal
  that lets a killed UI recover a finished answer. See 3.5.0 for the shipped behaviour.

## 3.1.4

- Thinking cards stop collapsing while the answer streams — the expand state no longer
  uses the streaming text as its `remember` key.
- Tool results gained the `[tool · identity]` prefix and `phone_shell` became a core tool
  whenever Shizuku is ready.
- Corrected the network guidance to match the device: no `curl` / `wget`, Python has
  only the standard library (no `pip` / `PIL` / `requests`), and `python3` is not a
  shell command.

## 3.1.3

- Single self-contained HTML became the default web deliverable, with instructions for
  opening it instead of a bare `file://` link.
- Relaunching the app returns to the last conversation instead of the home screen.
- An answer that hits the output budget continues automatically instead of ending the
  task.

## 3.1.2

- **The agent's tool guide is now organised around execution identity.** Three
  identities are spelled out: `phone_sh` / `phone_py` / `phone_js` run as the app
  uid (no `pm` / `am` / `settings` / `dumpsys`, no global `/proc`), `phone_shell`
  runs as Shizuku (uid 2000, the only place privileged commands work), and the
  accessibility tools only drive the UI. The guide also states that a success in
  one identity does not prove the other, and that a failure in one does not mean
  the phone cannot do it.
- **Negative evidence is banned.** Under the app uid, `ps -A` only sees the app's
  own processes and `/proc/net/tcp`, `ss`, `/data/app` are unreadable, so “I did
  not see it” is not “it is not running”.
- **Test before you claim.** Saying “unavailable / unsupported / permission
  denied” now requires a minimal real attempt; untested items must be labelled
  “unverified” instead of “should work”.
- **Runtime capabilities are injected into the system prompt** at the start of
  every run (accessibility on/off, Shizuku ready or not, bundled runtime
  identity), so the model no longer probes for them with `ps` or port scans.
  A final self-check block closes the system prompt.
- **Context-usage bar and per-turn timer in the Agent area.** Ten dashed
  segments, one per 10%, filled proportionally (minimum 1%, 0.1% precision); the
  window is selectable (64K / 128K / 256K / 1M, default 128K). The timer measures
  one turn from send to finish and is persisted per session.
- **Home screen split into two isolated zones.** The home screen only has the
  LLM and Agent entries; each conversation list lives inside its own zone.
  Agent uses the mint accent, LLM uses the warm accent.
- **Tool cards show the execution identity** (`App identity` / `Shizuku uid
  2000`) next to the parameters.
- **Fixed:** the tool-detail panel was always empty because the UI read `text`
  / `is_error` while the runner emits `content` / `isError`; session rows never
  showed rounds/steps because `usageStats` was never written.
- **Rounds:** the per-run ceiling is now 120 model round-trips (was 40), and the
  guide teaches batching (`;` / `&&` inside one `phone_sh` call, several tool
  calls in one turn). After 6 consecutive single-tool rounds the runner appends a
  batching hint.

## 3.1

- Raised the per-run ceiling from 40 to 120 rounds and added the batching hints.
- Added the context-usage bar and per-turn timer to the Agent area.
- Split the home screen into LLM / Agent zones and recoloured the two areas.
- Fixed the blank tool-detail panel and the missing rounds/steps in session rows.

## 3.0

- **Settings, rebuilt for scanning.** Every block is now a titled card with an accent bar
  (model endpoint / appearance / conversation / reading / character / more), so the
  screen can be read at a glance instead of one long undifferentiated list.
- **Speech is Qwen3-TTS only.** Besides the built-in system speech engine, DLA now
  talks to Alibaba Cloud Bailian Qwen3-TTS (`qwen3-tts-flash`) and nothing else: paste
  a Bailian API key, pick a voice (Cherry / Serena / Ethan / Chelsie, or type your own),
  choose the Beijing or Singapore region, and preview. Long replies are split by
  sentence (the API caps a request at 600 characters) and played back to back.
- **Read-aloud is off by default.** A one-time migration also turns it off for people
  upgrading from 2.x; the switch lives under Settings → Reading.
- **Docs for 3.0:** README now explains the LLM area, how DSH (DeepSeek Harness) was
  used to build this repo and roughly how much of the work it did, the author's role,
  and what “fully open source” means here.

## 2.5

- **Read replies aloud, with your own voice provider.** TTS is back with a switch,
  a per-message read-aloud chip and auto-read on reply. The default is the device's
  system speech engine; users can also add any OpenAI-compatible cloud TTS
  (`POST {baseUrl}/audio/speech`) with its own API key, model, voice, format and
  speed, switch between providers, preview, edit and delete them. Keys stay on the
  device.
- **Layered character settings (2.3).** The single persona textarea is now ten
  fields in five groups (core identity, personality & expression, relationship &
  background, boundaries, extra), stored as JSON; the old single-block persona is
  migrated into “extra” without losing a character.
- **AI completion and picture analysis (2.3).** “AI complete settings” asks the
  model to reason through emotional needs, factual consistency and user safety
  before writing, then fills **only empty fields** with a preview dialog. “Analyze a
  picture” sends a compressed portrait, fills appearance/identity/personality and
  can set the picture as the portrait in one step.
- **Text beats pictures (2.3).** The persona block declares the text as the only
  authority; the injected portrait comes with the textual appearance next to it, so
  a conflicting image cannot rewrite the character’s looks.

## 2.3

- (included above: layered persona, AI completion, picture analysis, text priority)

## 2.2

- **Termux is gone; the runtimes live in the APK.** The app now ships Android's own
  shell (toybox: awk / sed / grep / find / tar / unzip / sha256sum / timeout / nc …),
  CPython 3.12 through Chaquopy, Node.js 24 (libnode + launcher) and ripgrep.
  Commands run as the app identity; only `pm` / `am` / `settings` still need Shizuku.
  Four setup steps disappear: install Termux, `allow-external-apps`, MIUI foregrounding,
  and the RUN_COMMAND grant. The “Shizuku or Termux” admission gate is deleted — the
  Agent works on a bare install.
- **The web shell is gone.** `MainActivity`, the WebView host bridge, `KeyMigration`
  and the 4.3 MB `assets/public` bundle are removed; the native Compose UI is the only
  entry. The only remaining WebView is the isolated, offline Markdown renderer.
- **New agent tools.** `phone_sh` (core, app-uid shell), `phone_py` (Chaquopy,
  `sys.settrace` deadline so runaway loops get interrupted) and `phone_js`
  (Node child process). All three are approval-gated and truncated at 32 KB per stream.
- **Stopping really stops.** Commands start under `setsid`, write their pid, and are
  killed by process group *and* session on stop/timeout, so backgrounded scripts
  (`nohup … &`) die with the run.
- **Arm64 only.** Python 3.12+ and the prebuilt Node/ripgrep binaries are 64-bit;
  `abiFilters` is `arm64-v8a`. APK ≈ 48 MB (≈ 150 MB installed, mostly V8).
- **Settings.** The Termux panel is replaced by “运行环境”: Android/toybox version,
  Python, Node, ripgrep, working directory, and a self-check that runs one real command.

## 2.1

- **Copy is back, without turning the screen into a text field.** Long-press a user
  bubble or an assistant paragraph to open a copy panel: *Copy all* takes the whole
  message in one tap, and the text inside the panel is selectable so a part can be
  dragged out. Assistant answers also get a small copy button under the text. The
  long-press gesture only sits on the text, so scrolling the list still works.
- **Empty conversations are no longer saved.** Creating a chat keeps it in memory;
  the JSON file is written with the first message *or* the first keystroke of a
  draft. Drafts are debounced (500 ms) and saved per session, restored when you
  come back; a session that only has a draft is labelled “draft” in the list
  instead of looking like an empty shell. Leftovers from older versions are pruned
  on startup.
- **“Continue” now carries the whole context.** Agent requests start with the last
  eight turns (assistant turns include a tool-call digest), consecutive same-role
  turns are merged, and the plan/notes from `task_state` are persisted and replayed
  via `resumeTaskState`. Before this, hitting the round limit and replying
  “continue” made the model answer “this conversation is empty”.
- **Per-call tool timing.** Each tool card shows the duration of that call
  (seconds / minutes / hours) instead of “time since the run started”, which grew
  into numbers like `1484111ms`.
- **The agent can look at images.** New `phone_view_image(path)` reads a local
  jpg/png/webp/gif (longest edge 1280, JPEG 82, 32 MB cap) and returns it as an
  image block, so the model actually sees the picture instead of guessing from the
  filename. It is registered even when Shizuku is not ready — reading a file needs
  no special privilege — which makes it usable on Termux-only devices.
- **Image preview is no longer full-screen.** Tapping an image in a chat opens it
  centred at 90% × 72% with the conversation visible around it; tap anywhere or
  press back to close.
- VersionCode 37 / versionName 2.1.

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
