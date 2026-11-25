# Architecture

## Layers

```text
native UI (Kotlin, Jetpack Compose) — the only entry point
  ├─ NativeShellActivity.kt      page stack: Home / Zone(mode) / Chat / Settings / Advanced
  ├─ ChatEngine.kt               LLM + Agent request assembly, event consumption, session state
  ├─ Shatter.kt                  tap-to-shatter feedback (observes pointers, never consumes them)
  ├─ SoundFeedback.kt            taps / send / done / error, all on one HandlerThread
  ├─ MascotView.kt               v1 Cue / v2 Ming motion (draw-only, no layout impact)
  ├─ MarkdownCompose.kt          native Markdown rendering
  ├─ Markdown.kt                 isolated, offline KaTeX/mermaid WebView (renderer only)
  └─ WebShotReceiver.kt          bridges an off-screen web screenshot request into this window
                    │
                    ▼
android/ (Java + Kotlin, applicationId com.example.chatdemo)
  ├─ AgentClient                 UI-process facade: bindService + Messenger, cursor replay
  └─ :agent process
       ├─ AgentService           foreground service, result notifications, notification stop
       ├─ AgentRuntime           single-run state machine, event buffer, checkpoint
       ├─ AgentRunner            native LLM loop, tool injection, approvals, caching
       ├─ ProviderCompat         vendor adapter: presets, detection, per-vendor quirks
       ├─ OpenAiWire             Anthropic block model → OpenAI Chat Completions payloads
       ├─ AgentRunJournal        per-run journal on disk, claimed by the UI after a restart
       ├─ LocalTools             accessibility / Shizuku / PackageManager tools
       ├─ NativeShell / PythonTool / NodeTool / RuntimeTools / ProcessRunner   bundled runtimes (app uid)
       ├─ WebShot                phone_web_shot request/response side (runs in :agent)
       ├─ WebTools                web_search (provider split) + web_fetch (native HTTP)
       ├─ ShizukuShell           uid 2000 shell via Shizuku
       ├─ ChatDemoAccessibilityService   screen tree dumper
       └─ Diagnostics / AgentCheckpoint / AgentKeepAlive
```

## Process model

- The UI process owns the Compose surface; it connects to `AgentService` through
  `AgentClient`. Navigation is a stack of `Screen` values kept in `NativeShellActivity`
  (saved with `listSaver`), so back pops one level instead of resetting to Home.
- `AgentService`, `AgentRuntime`, `AgentRunner` and the accessibility service run in
  the `:agent` process. A UI crash or recreate only drops the binding; the run
  continues and the UI reattaches with `attach(requestId, cursor)`, which asks the
  service for the event slice it missed.
- `BootReceiver` / `HealthReceiver` resume a pending checkpoint with
  `ACTION_RESUME`, so the resume itself also happens in `:agent`.
- Stopping a run also terminates the process tree started by that run
  (`setsid` + pid file + `pkill -g/-s`), so backgrounded scripts do not survive.

## Model interface

Both zones share one endpoint configuration: `protocol` (`anthropic` | `openai`), base
URL, API key, model and provider id.

- **Two dialects.** Anthropic Messages is `POST {base}/messages` with `x-api-key`;
  OpenAI is `POST {base}/chat/completions` with a bearer token. `ChatEngine` keeps the
  internal block model (`text` / `image` / `tool_use` / `tool_result`) and both zones
  produce the same shapes; only the wire layer differs.
- **`OpenAiWire`** lowers the internal messages right before the request: system becomes
  `role:system`, assistant `tool_use` becomes `tool_calls` with a JSON string, user
  `tool_result` becomes standalone `role:tool` messages, images become `image_url` data
  URLs, and tool schemas become `{type:function, function:{…}}`. Streaming parses
  `choices[0].delta` (`content` / `reasoning_content` / `tool_calls[].arguments`) and maps
  `finish_reason` back to the internal stop reason (`tool_calls` → `tool_use`,
  `length` → `max_tokens`).
- **`ProviderCompat`** is pure logic shared by the settings UI and the loop (so the UI
  cannot advertise something the request would not send). It recognises the vendor from
  the base URL, then the model name, and selects: the output-limit field
  (`max_tokens` vs `max_completion_tokens`), whether `stream_options.include_usage` may
  be sent, whether `temperature` must be omitted (o-series / gpt-5), the thinking switch
  (`thinking` object / `enable_thinking` / `reasoning_effort`), whether server-side web
  tools exist (DeepSeek only) and the identity prompt (neutral for non-DeepSeek).
- **Switching protocol rewrites the endpoint** (`/anthropic/v1` ↔ `/v1`) and, in the
  other direction, `…/messages` / `…/chat/completions` suffixes are stripped when the
  model list is fetched.

Tool availability trimming (`connectTools`):

| Condition | Effect |
|---|---|
| Shizuku not ready | `phone_shell`, `phone_stop`, `phone_setting_put`, `phone_notifications` are not injected |
| Accessibility not enabled | `phone_screen`, `phone_wait`, `phone_screenshot`, `phone_tap_text` are not injected |
| Always available | bundled runtimes (`phone_sh` / `phone_py` / `phone_js`), files, PackageManager tools, `tool_search`, web, task memory |
| Shizuku ready | `phone_shell` is added to the core list directly (no `tool_search` round) |

## Request flow (Agent)

1. `ChatEngine.startAgent` builds the payload (session id, mode, model, apiKey,
   apiOrigin, protocol, provider, system, messages, mcpServerIds, autoApprove,
   `maxRounds`, optional plan/notes) and hands it to `AgentClient.start`.
2. `AgentRuntime` opens a `AgentRunJournal` for the run, starts the foreground service
   and writes an encrypted checkpoint. There is **no admission gate**: the bundled
   runtimes always work.
3. `AgentRunner.connectTools` injects the 13 core local tools (14 when Shizuku is ready),
   `tool_search`, the two web tools, MCP tools and the task-memory tools.
4. `AgentRunner.streamRound` posts to the configured endpoint in the selected dialect and
   parses the SSE stream (text / thinking / `tool_use` / usage).
5. `AgentRunner.executeTool` dispatches to `RuntimeTools` (shell / Python / Node),
   `LocalTools` (accessibility / Shizuku / PackageManager / file tools / `phone_web_shot`),
   `McpClient`, `WebTools` or `TaskTools`. Every result is prefixed with its source tag
   (`[tool · identity]`) before it goes back to the model.
6. **Truncated arguments are recoverable.** `parseArguments` returns null instead of
   throwing; the call is marked truncated, not executed, and answered with a hint that
   teaches `phone_write` + `phone_append` in ≤3000-character chunks. The round continues.
7. **Output budget overflow continues.** If a round ends on `max_tokens` with no tool
   call, the partial text is appended as an assistant message plus a “continue” nudge,
   up to 3 times, with a status event each time. If the output budget was consumed by
   thinking with no text at all, the watchdog downgrades **that round only**, then the
   next round thinks again (see “Thinking budget” below).
8. Events flow back through `AgentService` → `AgentClient` → `ChatEngine` state; the same
   events are folded into `AgentRunJournal`, which is flushed at most once per 700 ms.
9. On `done` / `stopped` / `error` the journal is finalised and broadcast, the checkpoint
   is cleared, a result notification is posted and the service stops.

## Thinking budget and tool rounds

DLA keeps the `thinking → tool → thinking` loop alive for long tasks instead of disabling
thinking for the rest of the run:

- A per-round character budget (12 000) plus a client-side watchdog aborts a round only
  when it has produced thinking but no text and no tool call. Repeated overruns tighten
  the next attempts (12K → 6K → 3K characters); a successful round resets it.
- The retry runs with thinking disabled **for that round only**; before it, the tail of
  the abandoned reasoning (≤600 characters) is appended to the nudge so the model can
  continue instead of re-deriving its plan. The retry reuses the same round number.
- **Thinking blocks are part of the protocol.** Anthropic Messages (including DeepSeek’s
  `/anthropic/v1`) rejects the next tool round if an assistant message produced under
  thinking lacks its `thinking` block and signature:
  `The content[].thinking in the thinking mode must be passed back to the API`.
  The SSE handler accumulates `thinking` and `signature_delta`; `assistantMessage()`
  prepends `{"type":"thinking","thinking":…,"signature":…}`; a watchdog-truncated round
  re-attaches the abandoned thinking so the recovered assistant message is still valid.
  The block is only sent on the Anthropic dialect.
- Claude gets a server-side budget (`thinking.budget_tokens=4096`); DeepSeek and other
  Anthropic-compatible endpoints rely on the client watchdog.

## Web tools

- `web_fetch` is native HTTP, independent of the provider: desktop Chrome UA, gzip,
  manual redirect handling (at most 4 requests per chain), 24 000 characters of text and
  3 MB of response,
  `Content-Type` / `<meta charset>` decoding, script/style/svg stripping, and a prefix
  guard that refuses `localhost`, `127.0.0.1`, `0.0.0.0`, `[::1]` and `10.` / `192.168.` /
  `169.254.` hosts.
- `web_search` keeps the official DeepSeek endpoint (`POST {origin}/v1/tools/search`)
  when the provider is DeepSeek, falling back to HTML search if it fails; for any other
  provider it goes straight to free HTML backends. Order depends on the query script:
  Chinese → Baidu → Bing CN → Bing → Bing RSS → DuckDuckGo; English → Bing → Bing CN →
  Baidu → Bing RSS → DuckDuckGo. Failures accumulate diagnostics (backend name, HTTP
  status, page size, whether `b_algo` was present) instead of a bare error.

## Web screenshot (phone_web_shot)

`:agent` cannot render a WebView (a WebView with no window draws black), so `WebShot`
sends a broadcast that `WebShotReceiver` (UI process) hands to
`NativeShellActivity.renderWebShot`: a WebView is attached to the current window, moved
outside the visible area, loaded from `file://`, given a CSS “animations off” injection
so the first frame is the final state, measured, drawn to a bitmap and saved to
`/sdcard/Download/DLA-web-shots/`. The path comes back by broadcast and the tool returns
the image as a base64 JPEG block plus the file path. Constraints: 2 MB HTML, 45 s
timeout, `full_page` capped at 2000 dp, and a clear error when DLA is not in the
foreground (the app is never pulled to the front for a screenshot).

## Feedback and notifications

- `Shatter` observes pointer events in the `Initial` pass on a host wrapper — it never
  consumes them, so buttons and list taps behave normally. A tap within 24 dp / 400 ms
  emits 7 rounded blocks (2.5–7 dp) plus a small ring for 430 ms, at most 3 bursts alive.
- `SoundFeedback` owns a `HandlerThread`; `ToneGenerator` and `MediaPlayer` are created
  and warmed up there so the first tap does not hitch the main thread. Tap / send are
  short synthesised tones; done / error are generated WAVs (880 → 1318 Hz ascending,
  523 → 330 Hz descending, ~0.35 s) played with `USAGE_MEDIA`, so completion is audible
  in the foreground as well as through the notification channel. MediaPlayers are kept
  in a strong-reference set until playback ends.
- `AgentService` owns two channels: `agent-run` (`IMPORTANCE_LOW`, the ongoing
  foreground-service notification) and `agent-result-v2` (`IMPORTANCE_HIGH`, default
  notification sound, vibration `[0, 80, 60, 120]`). The foreground notification is only
  posted while there is work and is removed with `stopForeground(STOP_FOREGROUND_REMOVE)`.
  A result notification carries the conclusion preview, rounds / steps / elapsed / last
  tool, and a `PendingIntent` back into the session
  (`NEW_TASK | CLEAR_TOP | SINGLE_TOP`).

## Two zones

LLM and Agent are two peer zones, not a chat box with an add-on:

- sessions carry a `mode` (`llm` / `agent`); the home screen only routes, and each
  zone lists only its own sessions;
- both share the same model endpoint, API key, protocol, provider and local store;
- the Agent zone adds the tool loop, iteration tree, one collapsible Process card for all
  rounds (expanded while running, collapsed once stored), approvals and the
  context-usage / turn-time bar; the LLM zone adds persona, speech and web search.

## Crash behavior

- UI process death does not stop `:agent`; reopening the app replays the missed events by
  cursor.
- A run that finished while the UI was dead is recovered from `files/agent-runs/<id>.json`
  (`ChatEngine.recoverRuns`): the journal carries text, thinking, tool cards, usage and
  task state, the message is written with its `runId`, and `SessionStore.hasRun` prevents
  a live event and a reclaimed journal from duplicating the same answer.
- The isolated Markdown WebView is a renderer only (no bridge, no network); if it dies
  the message falls back to the native renderer.
- Checkpoints encrypted with Android Keystore allow resuming after a process kill.

## Screen reading

`phone_screen` calls `ChatDemoAccessibilityService.dumpScreen()`, which walks
`getRootInActiveWindow()` with a 300-node cap and a 300 ms TTL cache. If the service is
unavailable the tool is not injected at all (the guide tells the model to ask the user to
enable it), instead of silently failing.

## Storage

- `files/native-sessions/<id>.json`: authoritative store for sessions and messages, plus
  `draft`, `taskState` and `usageStats` (rounds / steps / context tokens / turn time).
  Assistant messages carry the `runId` used for de-duplication.
- `files/agent-runs/<requestId>.json`: per-run journal (text, thinking, tools, usage,
  task state, status); deleted once the UI has claimed it.
- `files/diagnostics/events.log`: bounded ring log (256 KB) with tool names and stack
  traces; never message bodies.
- `SharedPreferences("dla-native")`: API key, base URL, model, protocol, provider, cached
  model list, persona, speech settings, theme, language, context window, notification
  preference.
- `files/agent-checkpoint`: encrypted run checkpoint (Android Keystore).
- `files/agent-uploads/`: files copied in for Agent tasks.
- `/sdcard/Download/DLA-web-shots/`: screenshots produced by `phone_web_shot`.

## Security boundary

The app is not a sandbox. Accessibility, Shizuku and the bundled shell grant capabilities
a malicious prompt could abuse. The public default is approval mode; full access is
opt-in. The notification stop action is the hard interrupt. The tool guide states the
three execution identities explicitly, tags every tool result with the identity that
produced it, and forbids negative evidence from the app uid's blind spots, so neither the
model nor the user can confuse an app-uid success with a privileged one.
