# Architecture

## Layers

```text
native UI (Kotlin, Jetpack Compose) — the only entry point
  ├─ NativeShellActivity.kt      Home / Zone(mode) / Chat / Settings / Advanced
  ├─ ChatEngine.kt               LLM + Agent request assembly, event consumption, session state
  ├─ MascotView.kt               v1 Cue / v2 Ming motion (draw-only, no layout impact)
  ├─ MarkdownCompose.kt          native Markdown rendering
  └─ Markdown.kt                 isolated, offline KaTeX/mermaid WebView (renderer only)
                    │
                    ▼
android/ (Java + Kotlin, applicationId com.example.chatdemo)
  ├─ AgentClient                 UI-process facade: bindService + Messenger, cursor replay
  └─ :agent process
       ├─ AgentService           specialUse foreground service + notification stop
       ├─ AgentRuntime           single-run state machine, event buffer, checkpoint
       ├─ AgentRunner            native LLM loop, tool injection, approvals, caching
       ├─ LocalTools             accessibility / Shizuku / PackageManager tools
       ├─ NativeShell / PythonTool / NodeTool / ProcessRunner   bundled runtimes (app uid)
       ├─ ShizukuShell           uid 2000 shell via Shizuku
       ├─ ChatDemoAccessibilityService   screen tree dumper
       └─ Diagnostics / AgentCheckpoint / AgentKeepAlive
```

## Process model

- The UI process owns the Compose surface and holds no Agent state; it connects to
  `AgentService` through `AgentClient`.
- `AgentService`, `AgentRuntime`, `AgentRunner` and the accessibility service run in
  the `:agent` process. A UI crash or recreate only drops the binding; the run
  continues and the UI reattaches with `attach(requestId, cursor)`, which asks the
  service for the event slice it missed.
- `BootReceiver` / `HealthReceiver` resume a pending checkpoint with
  `ACTION_RESUME`, so the resume itself also happens in `:agent`.
- Stopping a run also terminates the process tree started by that run
  (`setsid` + pid file + `pkill -g/-s`), so backgrounded scripts do not survive.

## Request flow (Agent)

1. `ChatEngine.startAgent` builds the payload (model, apiKey, messages, plan/notes,
   `maxRounds`, `autoApprove`) and hands it to `AgentClient.start`.
2. `AgentRuntime` starts the foreground service and writes an encrypted checkpoint.
   There is **no admission gate**: the bundled runtimes always work.
3. `AgentRunner.connectTools` injects the 13 core tools, `tool_search`, MCP tools
   and the task-memory tools. Backend-dependent tools are trimmed (see below).
4. `AgentRunner.streamRound` posts to the configured endpoint and parses the SSE
   stream (text / thinking / `tool_use` / usage).
5. `AgentRunner.executeTool` dispatches to `RuntimeTools` (shell / Python / Node),
   `LocalTools` (accessibility / Shizuku / PackageManager), `McpClient`,
   `WebTools` or `TaskTools`.
6. Events flow back through `AgentService` → `AgentClient` → `ChatEngine` state.
7. On `done` / `stopped` / `error` the checkpoint is cleared and the service stops.

Tool availability trimming (`connectTools`):

| Condition | Effect |
|---|---|
| Shizuku not ready | `phone_shell`, `phone_stop`, `phone_setting_put`, `phone_notifications` are not injected |
| Accessibility not enabled | `phone_screen`, `phone_wait`, `phone_screenshot`, `phone_tap_text` are not injected |
| Always available | bundled runtimes (`phone_sh` / `phone_py` / `phone_js`), files, PackageManager tools, `tool_search`, web, task memory |

## Two zones

LLM and Agent are two peer zones, not a chat box with an add-on:

- sessions carry a `mode` (`llm` / `agent`); the home screen only routes, and each
  zone lists only its own sessions;
- both share the same model endpoint, API key and local store;
- the Agent zone adds the tool loop, iteration tree, approvals and the
  context-usage / turn-time bar; the LLM zone adds persona, speech and web search.

## Crash behavior

- UI process death does not stop `:agent`; reopening the app replays the missed
  events by cursor.
- The isolated Markdown WebView is a renderer only (no bridge, no network); if it
  dies the message falls back to the native renderer.
- Checkpoints encrypted with Android Keystore allow resuming after a process kill.

## Screen reading

`phone_screen` calls `ChatDemoAccessibilityService.dumpScreen()`, which walks
`getRootInActiveWindow()` with a 300-node cap and a 300 ms TTL cache. If the
service is unavailable the tool is not injected at all (the guide tells the model
to ask the user to enable it), instead of silently failing.

## Storage

- `files/native-sessions/<id>.json`: authoritative store for sessions and
  messages, plus `draft`, `taskState` and `usageStats` (rounds / steps / context
  tokens / turn time). The per-turn timer is persisted here.
- `files/diagnostics/events.log`: bounded ring log (256 KB) with tool names and
  stack traces; never message bodies.
- `SharedPreferences("dla-native")`: API key, base URL, model, persona, speech
  settings, theme, language, context window.
- `files/agent-checkpoint`: encrypted run checkpoint (Android Keystore).
- `files/agent-uploads/`: files copied in for Agent tasks.

## Security boundary

The app is not a sandbox. Accessibility, Shizuku and the bundled shell grant
capabilities a malicious prompt could abuse. The public default is approval mode;
full access is opt-in. The notification stop action is the hard interrupt.
Since 3.1.2 the tool guide also states the three execution identities explicitly,
so neither the model nor the user can confuse an app-uid success with a
privileged one.
