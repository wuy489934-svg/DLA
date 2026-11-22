# Architecture

## Layers

```text
web/ (React + Zustand + IndexedDB)
  ├─ chat store / API client          (LLM mode)
  ├─ agent bridge  ── AndroidHost ──┐
  └─ WebView UI                     │
                                    ▼
android/ (Java, single Activity)
  ├─ MainActivity / WebView host
  ├─ HostBridge (attachments, files, Shizuku, diagnostics)
  ├─ AgentService (specialUse FGS + notification stop)
  ├─ AgentRuntime / AgentRunner (native LLM loop + tools)
  ├─ LocalTools / McpClient / WebTools
  ├─ ChatDemoAccessibilityService (in-process screen tree)
  └─ Diagnostics / AgentCheckpoint / AgentKeepAlive
```

## Request flow (Agent)

1. Web builds a JSON payload and calls `AndroidHost.agentStart`.
2. `AgentRuntime` writes an encrypted checkpoint and starts `AgentService`.
3. `AgentRunner` posts to the configured API, parses streamed tool calls.
4. Tools run through Shizuku, Accessibility, WebFetch, or MCP.
5. Events are delivered to the WebView through `window.__agentEvent`.
6. On completion the checkpoint is cleared and the service stops.

## Crash behavior

- Main WebView implements `onRenderProcessGone` and returns `true`.
- The active Agent run is stopped and the notification is updated.
- The old WebView is destroyed and a new one is created.
- Session data survives in IndexedDB; a JSON mirror under
  `files/host-sessions/` is restored if IndexedDB is empty.

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
