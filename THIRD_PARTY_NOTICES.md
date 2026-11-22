# Third-party notices

This is a summary, not a replacement for the license files shipped by each
dependency. Before a public release, regenerate a complete dependency license
report (for example with `pnpm licenses list` and the Android dependency
report) and attach it to the release.

## Runtime / web

| Component | License | Notes |
|---|---|---|
| deepseek-ai/chat-demo | MIT | upstream web UI; `web/LICENSE` |
| React / React DOM | MIT | |
| KaTeX | MIT | fonts have their own OFL terms |
| Rspack | MIT | build tool |
| idb | ISC | IndexedDB wrapper |
| fflate | MIT | compression |
| Lucide `bot` icon | ISC | launcher icon; `licenses/lucide-ISC.txt` |

## Android

| Component | License | Notes |
|---|---|---|
| AndroidX Core / WebKit | Apache-2.0 | |
| Shizuku API / provider | Apache-2.0 | external Shizuku app required |
| AndroidX ProfileInstaller | Apache-2.0 | transitive |

## Assets

The repository intentionally contains **no** third-party character artwork.
The launcher icon is the Lucide line icon above. User-supplied persona images
are stored locally and are not distributed with the app.
