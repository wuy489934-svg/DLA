# Architecture

## Layers

```text
native UI (Kotlin, Jetpack Compose)            web UI (React + Zustand + IndexedDB)
  ├─ NativeShellActivity / ChatEngine.kt         ├─ chat store / API client
  ├─ MarkdownCompose.kt (native rendering)       ├─ agent bridge ── AndroidHost ──┐
  └─ Markdown.kt (isolated KaTeX/mermaid WV)     └─ WebView UI                     │
                    │                                                             │
                    ▼                                                             ▼
android/ (Java + Kotlin, applicationId io.github.dla.agent)
  ├─ MainActivity / WebView host + HostBridge (attachments, files, Shizuku, Termux, diagnostics)
  ├─ AgentClient (UI-process facade: bindService + Messenger, cursor replay)
  └─ :agent process
       ├─ AgentService (specialUse FGS + notification stop)
       ├─ AgentRuntime / AgentRunner (native LLM loop + tools)
       ├─ LocalTools / TermuxTools / McpClient / WebTools
       ├─ TermuxBridge / TermuxResultService (RUN_COMMAND + result callback)
       ├─ ChatDemoAccessibilityService (screen tree)
       └─ Diagnostics / AgentCheckpoint / AgentKeepAlive
```

## Process model (2.0)

- The UI process owns the Compose surface (or the WebView shell) and holds no
  Agent state; it connects to `AgentService` through `AgentClient`.
- `AgentService`, `AgentRuntime`, the accessibility service and
  `TermuxResultService` run in the `:agent` process. A UI crash or recreate only
  drops the binding; the run continues and the UI reattaches with
  `agentAttach(requestId, cursor)`, which asks the service for the event slice it
  missed (600 ms cap, then it falls back to "no replay").
- One exception: if the WebView **renderer** process dies (`onRenderProcessGone`),
  the UI sends `rendererGone` and the runtime stops the active run, because the
  page that owned the event sink is gone.
- `BootReceiver` / `HealthReceiver` resume a pending checkpoint by starting the
  service with `ACTION_RESUME`, so the resume itself also happens in `:agent`.
- Stopping a run also terminates the Termux process group started by that run
  (`TermuxBridge.cancelAll()`), because those shells are children of Termux, not
  of DLA.

## Request flow (Agent)

1. The native UI builds the JSON payload in `ChatEngine` (the web UI does the same
   in its bridge) and hands it to `AgentClient.start`.
2. `AgentRuntime` checks availability — **Shizuku or Termux, either one is
   enough** — and refuses only when both are missing.
3. `AgentRuntime` writes an encrypted checkpoint and starts `AgentService`.
4. `AgentRunner` builds the tool set: 11 core device actions, `tool_search`, and
   `termux`/local extensions registered for on-demand discovery. Unavailable
   backends are not injected at all.
5. `AgentRunner` posts to the configured API, parses streamed tool calls.
6. Tools run through Accessibility, Shizuku, Termux (`RUN_COMMAND`), WebFetch, or
   MCP.
7. Events are delivered to the UI through `window.__agentEvent` (web) or
   `ChatEngine` state (native).
8. On completion the checkpoint is cleared and the service stops.

Full walkthrough: `docs/AGENT-RUNTIME.md`.

## Crash behavior

- Main WebView implements `onRenderProcessGone` and returns `true`.
- The renderer-gone signal goes to `AgentClient`, which forwards it to the
  runtime; the active run is stopped and the notification is updated.
- The old WebView is destroyed and a new one is created.
- Session data lives in the native session store (`SessionStore.kt`) and, for the
  web shell, in IndexedDB plus a JSON mirror under `files/host-sessions/`.

## Screen reading

`phone_screen` first calls `ChatDemoAccessibilityService.dumpScreen()`, which
walks `getRootInActiveWindow()` with a 300-node cap and a 300 ms TTL cache.
If the service is unavailable it falls back to `uiautomator dump` and prefixes
the result with `source: fallback`.

The accessibility service is optional and must be enabled by the user. It does
not run in the background to collect data; it is queried only by a tool call.

## Storage

- WebView IndexedDB: canonical session/message store.
- `files/host-sessions/*.json`: local mirror, text/metadata only.
- `files/diagnostics/events.log`: bounded event/stack log.
- `SharedPreferences`: settings; checkpoint encrypted with Android Keystore.

## Security boundary

The app is not a sandbox. Shizuku and Accessibility grant capabilities that a
malicious prompt could abuse. The public default is approval mode; full access
is opt-in. The notification stop action is the hard interrupt.
