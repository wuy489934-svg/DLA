import { createId } from '../../utils/id.js'

/** 原生 Agent 运行时通过 window.__agentEvent 回传的事件 */
export type AgentEvent =
  | { type: 'ready'; tools: { name: string; title?: string }[]; model?: string }
  | { type: 'status'; text: string }
  | { type: 'text'; delta: string }
  | { type: 'thinking'; delta: string }
  | {
      type: 'tool_start'
      id: string
      name: string
      input: string
      approval?: boolean
      round?: number
      parallelGroup?: number
      parallel?: boolean
    }
  | {
      type: 'tool_result'
      id: string
      name: string
      content: string
      isError: boolean
      elapsedMs: number
      image?: boolean
      round?: number
      parallelGroup?: number
      parallel?: boolean
      cached?: boolean
    }
  | {
      type: 'usage'
      inputTokens: number
      outputTokens: number
      cacheCreationInputTokens?: number
      cacheReadInputTokens?: number
      elapsedMs?: number
      ttftMs?: number
    }
  | { type: 'done'; stopReason?: string }
  | { type: 'error'; message: string }
  | { type: 'stopped' }

export type McpServer = {
  id: string
  name: string
  url: string
  token: string
  enabled: boolean
}

type RunState = {
  queue: AgentEvent[]
  wake: (() => void) | null
  closed: boolean
}

const runs = new Map<string, RunState>()
const mcpPending = new Map<
  string,
  (result: { ok: boolean; tools?: number; message?: string }) => void
>()

const push = (requestId: string, event: AgentEvent) => {
  const run = runs.get(requestId)
  if (!run || run.closed) {
    return
  }
  run.queue.push(event)
  run.wake?.()
  run.wake = null
}

if (typeof window !== 'undefined') {
  window.__agentEvent = (requestId, json) => {
    try {
      push(requestId, JSON.parse(json) as AgentEvent)
    } catch {
      // 非法事件直接丢弃，不阻塞后续流
    }
  }
  window.__mcpResult = (requestId, json) => {
    const resolve = mcpPending.get(requestId)
    if (!resolve) {
      return
    }
    mcpPending.delete(requestId)
    try {
      resolve(JSON.parse(json) as { ok: boolean; tools?: number; message?: string })
    } catch {
      resolve({ ok: false, message: '返回内容无法解析' })
    }
  }
}

/** Agent 只存在于 Android 原生壳里；浏览器/PWA 下返回 false */
export const isAgentAvailable = () =>
  typeof window !== 'undefined' && typeof window.AndroidHost?.agentStart === 'function'

export type AgentContentBlock = Record<string, unknown>

export type AgentPayload = {
  apiKey: string
  model: string
  apiOrigin: string
  /** Agent 使用的协议/服务；LLM 区固定 DeepSeek */
  apiProvider?: 'deepseek' | 'openai' | 'qwen' | 'anthropic' | 'custom'
  apiProtocol?: 'anthropic' | 'openai'
  apiBaseUrl?: string
  system: string
  messages: { role: 'user' | 'assistant'; content: string | AgentContentBlock[] }[]
  mcpServerIds: string[]
  maxRounds: number
  /** 完全权限：跳过危险工具审批 */
  autoApprove: boolean
}

export type AgentRun = {
  requestId: string
  events: AsyncGenerator<AgentEvent, void, unknown>
  stop: () => void
}

/** 启动一次原生 Agent 运行；事件通过窗口回调进入队列，按需消费。 */
export const startAgent = (payload: AgentPayload): AgentRun => {
  const host = window.AndroidHost
  if (!host?.agentStart) {
    throw new Error('当前环境没有 Agent 运行时')
  }
  const requestId = createId()
  const run: RunState = { queue: [], wake: null, closed: false }
  runs.set(requestId, run)
  host.agentStart(requestId, JSON.stringify(payload))

  const events = (async function* () {
    for (;;) {
      const event = run.queue.shift()
      if (event) {
        if (event.type === 'done' || event.type === 'error' || event.type === 'stopped') {
          run.closed = true
        }
        yield event
        if (run.closed) {
          return
        }
        continue
      }
      if (run.closed) {
        return
      }
      await new Promise<void>((resolve) => {
        run.wake = resolve
      })
    }
  })()

  return {
    requestId,
    events,
    stop: () => {
      host.agentStop?.(requestId)
      run.closed = true
      run.wake?.()
      run.wake = null
    },
  }
}

const readServers = (): McpServer[] => {
  try {
    const raw = window.AndroidHost?.mcpServers?.()
    return raw ? (JSON.parse(raw) as McpServer[]) : []
  } catch {
    return []
  }
}

export const listMcpServers = readServers

export const saveMcpServer = (server: Omit<McpServer, 'id'> & { id?: string }) => {
  const id = server.id ?? createId()
  window.AndroidHost?.mcpSaveServer?.(JSON.stringify({ ...server, id }))
  return id
}

export const deleteMcpServer = (id: string) => {
  window.AndroidHost?.mcpDeleteServer?.(id)
}

/** 测试连接并拉取工具列表；仅 Android 原生壳可用。 */
export const testMcpServer = (server: Omit<McpServer, 'id'> & { id?: string }) =>
  new Promise<{ ok: boolean; tools?: number; message?: string }>((resolve) => {
    const host = window.AndroidHost
    if (!host?.mcpTestServer) {
      resolve({ ok: false, message: '仅 Android App 内可用' })
      return
    }
    const requestId = createId()
    mcpPending.set(requestId, resolve)
    host.mcpTestServer(requestId, JSON.stringify({ ...server, id: server.id ?? 'test' }))
    window.setTimeout(() => {
      if (mcpPending.delete(requestId)) {
        resolve({ ok: false, message: '连接超时' })
      }
    }, 15000)
  })
