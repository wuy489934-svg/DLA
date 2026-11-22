import clsx from 'clsx'
import { useEffect, useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { approveAgentTool } from '../../services/chat/store.js'
import type { AgentToolPart, ReplyBlock } from '../../services/chat/types.js'
import { Markdown } from '../markdown/Markdown.js'
import {
  IconChevronDownOutline14,
  IconChevronRightOutline14,
  IconCopyOutline16,
} from '../base/icons.js'
import { showToast } from '../base/toastState.js'
import styles from './AgentToolCard.module.css'
import './thinking.markdown.css'

const pretty = (value: string) => {
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

type TimelineStep = {
  key: string
  thinking: string
  tool?: AgentToolPart
}

const buildSteps = (blocks: ReplyBlock[]): TimelineStep[] => {
  const steps: TimelineStep[] = []
  let thought = ''
  for (const block of blocks) {
    if (block.type === 'thinking') {
      thought += block.thinking
    } else if (block.type === 'agent_tool') {
      steps.push({ key: block.id, thinking: thought.trim(), tool: block })
      thought = ''
    }
  }
  if (thought.trim()) {
    steps.push({ key: 'tail', thinking: thought.trim() })
  }
  return steps
}

/**
 * Agent 时间线：每次工具调用与它前面的思考绑定成一个独立折叠层。
 * 只有最新一层默认展开，旧层在新调用出现时自动收起，仍可手动点开。
 */
export const AgentTimeline = ({
  blocks,
  sessionId,
  streaming,
}: {
  blocks: ReplyBlock[]
  sessionId: string
  streaming: boolean
}) => {
  const steps = buildSteps(blocks)
  if (steps.length === 0) {
    return null
  }
  return (
    <div className={styles.timeline}>
      {steps.map((step, index) => (
        <AgentTimelineStep
          key={step.key}
          step={step}
          sessionId={sessionId}
          streaming={streaming}
          latest={index === steps.length - 1}
        />
      ))}
    </div>
  )
}

const AgentTimelineStep = ({
  step,
  sessionId,
  streaming,
  latest,
}: {
  step: TimelineStep
  sessionId: string
  streaming: boolean
  latest: boolean
}) => {
  const t = useTranslate()
  const [open, setOpen] = useState(latest)
  useEffect(() => {
    setOpen(latest)
  }, [latest])
  return (
    <div className={styles.step}>
      {step.thinking ? (
        <div className={styles.think}>
          <button
            type="button"
            className={styles.thinkHead}
            aria-expanded={open}
            onClick={() => setOpen((value) => !value)}
          >
            <span className={styles.thinkDot} />
            <span>{latest && streaming ? t('messageThinking') : t('agentThinkingTitle')}</span>
            {open ? <IconChevronDownOutline14 /> : <IconChevronRightOutline14 />}
          </button>
          {open ? (
            <div className={clsx(styles.thinkBody, 'ds-think-content')}>
              <Markdown
                content={step.thinking}
                loading={latest && streaming}
                cacheKey={`agent-think-${step.key}`}
                disableLink
              />
            </div>
          ) : null}
        </div>
      ) : null}
      {step.tool ? (
        <AgentToolCard tool={step.tool} sessionId={sessionId} latest={latest} />
      ) : null}
    </div>
  )
}

const AgentToolCard = ({
  tool,
  sessionId,
  latest,
}: {
  tool: AgentToolPart
  sessionId: string
  latest: boolean
}) => {
  const t = useTranslate()
  const waiting = tool.status === 'waiting'
  const [open, setOpen] = useState(waiting)
  // 旧层自动收起；最新层保持用户当前状态，审批时强制展开
  useEffect(() => {
    if (!latest) {
      setOpen(false)
    }
  }, [latest])

  useEffect(() => {
    if (waiting) {
      setOpen(true)
    }
  }, [waiting])

  const statusText =
    tool.status === 'waiting'
      ? t('agentToolWaiting')
      : tool.status === 'running'
        ? t('agentToolRunning')
        : tool.status === 'error'
          ? t('agentToolFailed')
          : tool.elapsedMs
            ? `${(tool.elapsedMs / 1000).toFixed(1)}s`
            : t('agentToolDone')

  const copy = async () => {
    const text = tool.result ?? ''
    try {
      await navigator.clipboard.writeText(text)
      showToast({ key: 'copied' })
    } catch {
      showToast({ key: 'copyFailed' })
    }
  }

  return (
    <div
      className={clsx(
        styles.card,
        tool.status === 'error' && styles.cardError,
        waiting && styles.cardWaiting
      )}
      data-agent-tool-id={tool.id}
    >
      <button
        type="button"
        className={styles.head}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span
          className={clsx(
            styles.dot,
            waiting
              ? styles.waiting
              : tool.status === 'running'
                ? styles.running
                : tool.status === 'error'
                  ? styles.failed
                  : styles.done
          )}
        />
        <span className={styles.name}>{tool.name}</span>
        <span className={styles.status}>{statusText}</span>
        <span className={styles.chevron}>
          {open ? <IconChevronDownOutline14 /> : <IconChevronRightOutline14 />}
        </span>
      </button>
      {open ? (
        <div className={styles.body}>
          {tool.input ? (
            <>
              <div className={styles.label}>{t('agentToolInput')}</div>
              <pre className={styles.pre}>{pretty(tool.input)}</pre>
            </>
          ) : null}
          {tool.result ? (
            <>
              <div className={styles.labelRow}>
                <span className={styles.label}>{t('agentToolResult')}</span>
                <button type="button" className={styles.copy} onClick={() => void copy()}>
                  <IconCopyOutline16 />
                  {t('copy')}
                </button>
              </div>
              <pre className={styles.pre}>{tool.result}</pre>
            </>
          ) : null}
          {waiting ? (
            <div className={styles.approval}>
              <span>{t('agentToolApprovalHint')}</span>
              <div className={styles.approvalActions}>
                <button
                  type="button"
                  className={styles.deny}
                  onClick={() => approveAgentTool(sessionId, tool.id, false)}
                >
                  {t('agentToolDeny')}
                </button>
                <button
                  type="button"
                  className={styles.approve}
                  onClick={() => approveAgentTool(sessionId, tool.id, true)}
                >
                  {t('agentToolApprove')}
                </button>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
