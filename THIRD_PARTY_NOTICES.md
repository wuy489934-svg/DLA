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
| KaTeX | MIT | web UI dependency; fonts have their own OFL terms |
| Rspack | MIT | build tool |
| idb | ISC | IndexedDB wrapper |
| fflate | MIT | compression |

## Android

| Component | License | Notes |
|---|---|---|
| AndroidX Core / WebKit | Apache-2.0 | |
| AndroidX Activity / Compose (BOM 2024.09.03) | Apache-2.0 | native UI |
| Kotlin / kotlinx.coroutines | Apache-2.0 | Kotlin 2.1.0 |
| Shizuku API / provider | Apache-2.0 | external Shizuku app required |
| AndroidX ProfileInstaller | Apache-2.0 | transitive |
| KaTeX 0.16.11 (bundled) | MIT | `assets/renderer/`, offline math, fonts under OFL |
| mermaid 10.9.1 (bundled) | MIT | `assets/renderer/`, offline diagrams |

| Node.js 24.22.1 (`libnode.so`, `libdshnode.so`) | MIT | arm64 build from DSH-Android prebuilts (Apache-2.0) |
| ripgrep 15.2.0 (`librg.so`) | MIT / Unlicense | arm64 build from DSH-Android prebuilts (Apache-2.0) |
| CPython 3.12 (Chaquopy runtime) | PSF-2.0 | loaded through Chaquopy (MIT) |
| Chaquopy 17.2.0 | MIT | Python runtime and Gradle plugin |
| Android toybox (`/system/bin/sh`) | 0BSD | shipped by the device, not bundled |

## Assets

The repository intentionally contains **no** third-party character artwork.
The launcher icon and the in-app mascot art (v1 Cue avatar, v2 Ming beacon) are
original DLA artwork (see `NOTICE`); the preview images in `docs/assets/` and
`docs/mascot/` are generated from the same source. User-supplied persona text
and portraits are optional, stored locally, and are not distributed with the app.
