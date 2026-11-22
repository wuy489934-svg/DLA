import type { MessageUsage } from './api/types.js'
import type { Session, SessionUsageStats } from './chat/types.js'

export const emptyUsageStats = (): SessionUsageStats => ({
  rounds: 0,
  steps: 0,
  inputTokens: 0,
  cacheCreationTokens: 0,
  cacheReadTokens: 0,
  outputTokens: 0,
  lastTps: 0,
  lastTtftMs: 0,
})

/** 旧会话没有持久化统计时，从消息反推 tokens/步数/轮次。 */
export const deriveUsageStats = (session: Session): SessionUsageStats => {
  const stats = emptyUsageStats()
  for (const message of session.messages) {
    if (message.role === 'user') {
      stats.rounds += 1
      continue
    }
    if (message.usage) {
      stats.inputTokens += message.usage.input_tokens
      stats.cacheCreationTokens += message.usage.cache_creation_input_tokens
      stats.cacheReadTokens += message.usage.cache_read_input_tokens
      stats.outputTokens += message.usage.output_tokens
    }
    for (const block of message.blocks) {
      if (block.type === 'agent_tool') {
        stats.steps += 1
      }
    }
  }
  return stats
}

export const getSessionUsageStats = (session: Session): SessionUsageStats =>
  session.usageStats ?? deriveUsageStats(session)

export const addUsage = (
  stats: SessionUsageStats,
  usage: MessageUsage | undefined,
  tps = 0,
  ttftMs = 0
): SessionUsageStats => {
  if (!usage) {
    return stats
  }
  return {
    ...stats,
    inputTokens: stats.inputTokens + usage.input_tokens,
    cacheCreationTokens: stats.cacheCreationTokens + usage.cache_creation_input_tokens,
    cacheReadTokens: stats.cacheReadTokens + usage.cache_read_input_tokens,
    outputTokens: stats.outputTokens + usage.output_tokens,
    lastTps: tps > 0 ? tps : stats.lastTps,
    lastTtftMs: ttftMs > 0 ? ttftMs : stats.lastTtftMs,
  }
}

/** 紧凑显示 365M / 12.3K */
export const formatTokenCount = (value: number): string => {
  if (value >= 1_000_000_000) {
    return `${(value / 1_000_000_000).toFixed(2)}B`
  }
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 0 : 1)}M`
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(value >= 10_000 ? 0 : 1)}K`
  }
  return String(Math.round(value))
}

export const cacheHitPercent = (stats: SessionUsageStats): number => {
  const total = stats.inputTokens + stats.cacheCreationTokens + stats.cacheReadTokens
  return total > 0 ? Math.round((stats.cacheReadTokens / total) * 100) : 0
}
