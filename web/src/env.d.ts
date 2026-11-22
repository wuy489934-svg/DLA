declare module '*.module.css' {
  const classes: Record<string, string>
  export default classes
}

declare module '*.css' {}

declare module '*.webp' {
  const url: string
  export default url
}

/** 公开 API 的地址，见 rspack.config.ts 的 createConfig */
declare const __API_ORIGIN__: string

/** Android 壳暴露的 Agent / MCP 接口；浏览器环境下为 undefined */
interface AndroidAgentHost {
  agentStart?: (requestId: string, payload: string) => void
  agentStop?: (requestId: string) => void
  agentApprove?: (requestId: string, toolId: string, allowed: boolean) => void
  attachmentBegin?: (name: string, mime: string) => string
  attachmentChunk?: (id: string, base64: string) => boolean
  attachmentEnd?: (id: string) => string
  accessibilityStatus?: () => string
  openAccessibilitySettings?: () => void
  exportDiagnostics?: () => string
  saveSessionSnapshot?: (json: string) => void
  loadSessionSnapshots?: () => string
  mcpServers?: () => string
  mcpSaveServer?: (json: string) => void
  mcpDeleteServer?: (id: string) => void
  mcpTestServer?: (requestId: string, json: string) => void
  shizukuStatus?: () => string
  shizukuRequest?: () => void
  shizukuOpen?: () => void
  shizukuInstall?: () => void
  shellTest?: (command: string) => string
  httpRequest?: (
    method: string,
    url: string,
    headersJson: string,
    body: string
  ) => string
  debugTool?: (name: string, argsJson: string) => string
  toast?: (message: string) => void
  setDarkMode?: (dark: boolean) => void
}

interface Window {
  AndroidHost?: AndroidAgentHost & Record<string, unknown>
  __agentEvent?: (requestId: string, json: string) => void
  __mcpResult?: (requestId: string, json: string) => void
}

/** Prism 语言包通过副作用注册到全局 Prism */
declare module 'prismjs/components/*' {}

/** KaTeX 化学公式扩展通过副作用注册到 KaTeX */
declare module 'katex/contrib/mhchem' {}
