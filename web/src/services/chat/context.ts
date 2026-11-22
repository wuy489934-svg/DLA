import type { Message, Session } from './types.js'
import { getVisibleMessages } from './branches.js'

/** 当前模型上下文窗口；按用户指定使用 1M。 */
export const CONTEXT_LIMIT = 1_000_000

/** 粗略估算：中文/代码混合场景下约 4 字符/token，只用于无 usage 时的展示。 */
export const estimateTokens = (text: string): number => Math.ceil(text.length / 4)

const blockText = (message: Message, maxToolChars = Infinity): string => {
  if (message.role === 'user') {
    return message.content
  }
  return message.blocks
    .map((block) => {
      if (block.type === 'text') {
        return block.text
      }
      if (block.type === 'thinking') {
        return block.thinking
      }
      if (block.type === 'agent_tool') {
        const result = block.result ?? ''
        const clipped =
          Number.isFinite(maxToolChars) && result.length > maxToolChars
            ? `${result.slice(0, Math.floor(maxToolChars * 0.75))}\n[... 工具结果已剪枝 ${result.length - maxToolChars} 字符 ...]\n${result.slice(-Math.floor(maxToolChars * 0.25))}`
            : result
        return `${block.name}\n${block.input}\n${clipped}`
      }
      return ''
    })
    .join('\n')
}

/** 最近一次请求 usage 对应的上下文 token（输入 + 缓存），没有 usage 时估算全部历史。 */
export const getContextTokens = (session: Session): number => {
  const messages = getVisibleMessages(session)
  if (session.contextSummary) {
    const cutoff = session.contextSummaryCutoff ?? 0
    let total = estimateTokens(session.contextSummary)
    for (const message of messages) {
      if (message.createdAt > cutoff) {
        total += estimateTokens(blockText(message)) + 8
      }
    }
    return total
  }
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i]
    if (message.role === 'assistant' && message.usage) {
      return (
        message.usage.input_tokens +
        message.usage.cache_creation_input_tokens +
        message.usage.cache_read_input_tokens +
        message.usage.output_tokens
      )
    }
  }
  return messages.reduce((sum, message) => sum + estimateTokens(blockText(message)) + 8, 0)
}

export const getContextPercent = (session: Session): number => {
  const raw = Math.min(100, Math.max(0, (getContextTokens(session) / CONTEXT_LIMIT) * 100))
  return raw >= 10 ? Math.round(raw) : Math.round(raw * 10) / 10
}

/** 至少保留最近 4 轮原文；更早的轮次才值得压缩。 */
export const getCompressibleRounds = (session: Session, maxToolChars = Infinity) => {
  const cutoff = session.contextSummaryCutoff ?? 0
  const messages = getVisibleMessages(session)
  const rounds: { text: string; cutoff: number }[] = []
  for (let i = 0; i + 1 < messages.length; i++) {
    const [user, reply] = [messages[i], messages[i + 1]]
    if (user.role !== 'user' || reply.role !== 'assistant') {
      continue
    }
    if (user.createdAt <= cutoff) {
      i += 1
      continue
    }
    rounds.push({
      text: `用户：${user.content}\n助手：${blockText(reply, maxToolChars)}`,
      cutoff: reply.createdAt,
    })
    i += 1
  }
  return rounds
}
