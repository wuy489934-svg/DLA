# Privacy

DLA has no analytics, telemetry, crash-reporting SDK, or account system.

## What is stored on the device

- Chat sessions and messages in the WebView's IndexedDB.
- A local JSON mirror of sessions under the app's private files directory, used as a crash-recovery fallback.
- Settings, including the API key, in local storage.
- A bounded diagnostics log (event names and stack traces; no message bodies).
- Attachments in app-private storage.

## What leaves the device

Only requests you initiate:

- chat/Agent requests to the API origin you configure;
- optional MCP calls to servers you configure;
- web search/fetch only when a tool or the model requests it;
- opening external links in your browser.

There is no DLA-operated backend.

## Permissions

- `QUERY_ALL_PACKAGES`: enumerate installed apps for app-launch tools.
- `RECORD_AUDIO`: requested only when you use the microphone; denied by default.
- Accessibility: optional; used for in-process screen reading.
- Shizuku: optional; used for shell-level tools.

## Deleting data

Use Android Settings → Apps → DLA → Storage → Clear data. This removes sessions, settings, attachments, diagnostics, and the API key. Uninstalling also removes app-private data.

## Contact

Open a GitHub issue for non-sensitive privacy questions. For vulnerabilities, use `SECURITY.md`.
