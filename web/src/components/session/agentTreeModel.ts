import { getVisibleMessages } from '../../services/chat/branches.js'
import type { AgentToolPart, Session } from '../../services/chat/types.js'

/** 计划节点解析结果；task_plan 的 input JSON 形如 { steps: string[], current?: number } */
export type PlanView = {
  steps: string[]
  current: number | null
}

/** task_plan / task_note 的附加展示信息，按块对象缓存，流式更新时避免重复解析 JSON */
export type ToolExtra = {
  plan?: PlanView
  note?: string
  /** 计划/笔记节点在行内显示的类型标签 */
  tag?: 'plan' | 'note'
}

export type ToolNode = {
  tool: AgentToolPart
  extra: ToolExtra
  /** 会话内第几次工具调用，从 1 开始 */
  index: number
  messageId: string
}

/** 同一轮内的分组：parallelGroup > 0 表示多个工具并行执行 */
export type TreeSegment = {
  key: string
  group: number
  parallel: boolean
  nodes: ToolNode[]
}

/** 原生 Agent 的一次迭代轮次（同一助理消息内 round 相同的工具） */
export type TreeRound = {
  key: string
  round: number
  messageId: string
  nodes: ToolNode[]
  segments: TreeSegment[]
  elapsedMs: number
  status: AgentToolPart['status']
}

export type AgentTreeModel = {
  /** 旧数据没有 round 字段时为 false，退化为顺序步骤列表 */
  roundMode: boolean
  steps: ToolNode[]
  rounds: TreeRound[]
  elapsedMs: number
  errors: number
  waiting: ToolNode[]
  running: ToolNode[]
}

const EMPTY_EXTRA: ToolExtra = {}
const extraCache = new WeakMap<AgentToolPart, ToolExtra>()

const asRecord = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null

const parseInput = (input: string): Record<string, unknown> | null => {
  try {
    return asRecord(JSON.parse(input) as unknown)
  } catch {
    return null
  }
}

const parseExtra = (tool: AgentToolPart): ToolExtra => {
  const input = parseInput(tool.input)
  if (tool.name === 'task_plan') {
    const rawSteps = input?.['steps']
    const steps = Array.isArray(rawSteps)
      ? rawSteps
          .filter((step): step is string => typeof step === 'string' && step.trim().length > 0)
          .map((step) => step.trim())
      : []
    const rawCurrent = input?.['current']
    const current =
      typeof rawCurrent === 'number' && Number.isFinite(rawCurrent)
        ? Math.min(steps.length, Math.max(1, Math.round(rawCurrent)))
        : null
    return steps.length > 0 ? { plan: { steps, current }, tag: 'plan' } : { tag: 'plan' }
  }
  const rawText = input?.['text']
  const text = typeof rawText === 'string' ? rawText.trim() : ''
  return { note: text || tool.input.trim() || undefined, tag: 'note' }
}

/** 获取 task_plan / task_note 的展示信息；其他工具返回共享空对象 */
export const getToolExtra = (tool: AgentToolPart): ToolExtra => {
  if (tool.name !== 'task_plan' && tool.name !== 'task_note') {
    return EMPTY_EXTRA
  }
  const cached = extraCache.get(tool)
  if (cached) {
    return cached
  }
  const extra = parseExtra(tool)
  extraCache.set(tool, extra)
  return extra
}

const STATUS_PRIORITY: Record<AgentToolPart['status'], number> = {
  error: 0,
  waiting: 1,
  running: 2,
  done: 3,
}

/** 轮次/分组状态：错误 > 待确认 > 运行中 > 已完成 */
const pickStatus = (nodes: ToolNode[]): AgentToolPart['status'] => {
  let status: AgentToolPart['status'] = 'done'
  for (const node of nodes) {
    if (STATUS_PRIORITY[node.tool.status] < STATUS_PRIORITY[status]) {
      status = node.tool.status
    }
  }
  return status
}

const buildSegments = (nodes: ToolNode[]): TreeSegment[] => {
  const segments: TreeSegment[] = []
  for (const node of nodes) {
    const group = typeof node.tool.parallelGroup === 'number' ? node.tool.parallelGroup : 0
    const last = segments.at(-1)
    if (group > 0 && last?.parallel && last.group === group) {
      last.nodes.push(node)
    } else {
      segments.push({
        key: `${node.tool.id}:${segments.length}`,
        group,
        parallel: group > 0,
        nodes: [node],
      })
    }
  }
  return segments
}

const sumElapsed = (nodes: ToolNode[]): number =>
  nodes.reduce((total, node) => total + (node.tool.elapsedMs ?? 0), 0)

/**
 * 从当前会话可见分支的助理消息中收集 agent_tool 块。
 * 有 round 字段时按「助理消息 + round」分组，同轮的并行工具再按 parallelGroup 分组；
 * 旧数据没有 round 时保持顺序步骤列表，由面板按步骤 1/2... 展示。
 */
export const buildAgentTree = (session: Session): AgentTreeModel => {
  const steps: ToolNode[] = []
  for (const message of getVisibleMessages(session)) {
    if (message.role !== 'assistant') {
      continue
    }
    for (const block of message.blocks) {
      if (block.type !== 'agent_tool') {
        continue
      }
      steps.push({
        tool: block,
        extra: getToolExtra(block),
        index: steps.length + 1,
        messageId: message.id,
      })
    }
  }

  const roundMode = steps.some((node) => typeof node.tool.round === 'number')
  const rounds: TreeRound[] = []
  if (roundMode) {
    let current: TreeRound | null = null
    for (const node of steps) {
      const round = typeof node.tool.round === 'number' ? node.tool.round : 0
      if (!current || current.messageId !== node.messageId || current.round !== round) {
        current = {
          key: `${node.messageId}:${round}:${rounds.length}`,
          round,
          messageId: node.messageId,
          nodes: [],
          segments: [],
          elapsedMs: 0,
          status: 'done',
        }
        rounds.push(current)
      }
      current.nodes.push(node)
    }
    for (const round of rounds) {
      round.segments = buildSegments(round.nodes)
      round.status = pickStatus(round.nodes)
      round.elapsedMs = sumElapsed(round.nodes)
    }
  }

  let elapsedMs = 0
  let errors = 0
  const waiting: ToolNode[] = []
  const running: ToolNode[] = []
  for (const node of steps) {
    elapsedMs += node.tool.elapsedMs ?? 0
    if (node.tool.status === 'error') {
      errors += 1
    } else if (node.tool.status === 'waiting') {
      waiting.push(node)
    } else if (node.tool.status === 'running') {
      running.push(node)
    }
  }

  return { roundMode, steps, rounds, elapsedMs, errors, waiting, running }
}

/** 紧凑耗时文案，与工具卡保持一致：1 秒内保留一位小数，10 秒以上取整 */
export const formatDuration = (ms: number): string => {
  if (ms < 1000) {
    return `${(ms / 1000).toFixed(1)}s`
  }
  const seconds = ms / 1000
  return seconds < 10 ? `${seconds.toFixed(1)}s` : `${Math.round(seconds)}s`
}
