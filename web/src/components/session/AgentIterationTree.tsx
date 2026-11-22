import clsx from 'clsx'
import {
  memo,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { useIsMobile } from '../../app/viewport.js'
import type { AgentToolPart, Session } from '../../services/chat/types.js'
import { IconChevronDownOutline14, IconChevronRightOutline14 } from '../base/icons.js'
import {
  buildAgentTree,
  formatDuration,
  type PlanView,
  type ToolExtra,
  type ToolNode,
  type TreeRound,
} from './agentTreeModel.js'
import styles from './AgentIterationTree.module.css'

const STATUS_LABEL: Record<AgentToolPart['status'], string> = {
  waiting: '等待确认',
  running: '运行中',
  done: '已完成',
  error: '失败',
}

const DOT_CLASS: Record<AgentToolPart['status'], string> = {
  waiting: styles.dotWaiting,
  running: styles.dotRunning,
  done: styles.dotDone,
  error: styles.dotError,
}

const toolTimeLabel = (tool: AgentToolPart, now: number): string => {
  if (tool.status === 'waiting') {
    return '待确认'
  }
  if (tool.status === 'running') {
    return typeof tool.startedAt === 'number'
      ? formatDuration(Math.max(0, now - tool.startedAt))
      : '运行中'
  }
  if (typeof tool.elapsedMs === 'number' && tool.elapsedMs > 0) {
    return formatDuration(tool.elapsedMs)
  }
  return tool.status === 'error' ? '失败' : '完成'
}

const roundTimeLabel = (round: TreeRound): string => {
  if (round.status === 'waiting') {
    return '待确认'
  }
  if (round.status === 'running') {
    return '运行中'
  }
  if (round.elapsedMs > 0) {
    return formatDuration(round.elapsedMs)
  }
  return round.status === 'error' ? '失败' : '完成'
}

/** 运行中的工具按 startedAt 实时跳秒；其他状态不触发重渲染 */
const useLiveNow = (enabled: boolean): number => {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!enabled) {
      return
    }
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [enabled])
  return now
}

const PlanList = ({ plan }: { plan: PlanView }) => (
  <div className={styles.plan}>
    <div className={styles.planHead}>
      <span>计划</span>
      <span>
        {plan.current !== null ? `${plan.current}/${plan.steps.length}` : `${plan.steps.length} 步`}
      </span>
    </div>
    <ol className={styles.planSteps}>
      {plan.steps.map((step, index) => {
        const position = index + 1
        const current = plan.current === position
        const done = plan.current !== null && position < plan.current
        return (
          <li
            key={`${position}-${step}`}
            className={clsx(
              styles.planStep,
              current && styles.planStepCurrent,
              done && styles.planStepDone
            )}
          >
            <span className={styles.planMarker} />
            <span className={styles.planText}>{step}</span>
          </li>
        )
      })}
    </ol>
  </div>
)

const Note = ({ text }: { text: string }) => (
  <div className={styles.note} title={text}>
    {text}
  </div>
)

const Leaf = memo(function Leaf({
  tool,
  extra,
  index,
  badge,
  timeLabel,
  active,
  onFocus,
}: {
  tool: AgentToolPart
  extra: ToolExtra
  index: number
  badge: string
  timeLabel: string
  active: boolean
  onFocus: (id: string) => void
}) {
  return (
    <div
      className={clsx(
        styles.leaf,
        active && styles.leafActive,
        tool.status === 'error' && styles.leafError
      )}
    >
      <button
        type="button"
        className={styles.leafButton}
        title={`步骤 ${index} · ${tool.name} · ${STATUS_LABEL[tool.status]}`}
        aria-label={`${tool.name}，${STATUS_LABEL[tool.status]}，${timeLabel}`}
        onClick={() => onFocus(tool.id)}
      >
        <span className={clsx(styles.dot, DOT_CLASS[tool.status])} />
        {badge ? <span className={styles.badge}>{badge}</span> : null}
        <span className={styles.name}>{tool.name}</span>
        {extra.tag ? (
          <span className={styles.tag}>{extra.tag === 'plan' ? '计划' : '笔记'}</span>
        ) : null}
        {tool.cached ? <span className={styles.cacheTag}>缓存</span> : null}
        <span className={styles.time}>{timeLabel}</span>
      </button>
      {extra.plan ? <PlanList plan={extra.plan} /> : null}
      {extra.note ? <Note text={extra.note} /> : null}
    </div>
  )
})

const RoundBlock = memo(function RoundBlock({
  round,
  now,
  activeId,
  onFocus,
}: {
  round: TreeRound
  now: number
  activeId: string | null
  onFocus: (id: string) => void
}) {
  const only = round.nodes[0]
  // 单工具轮次直接压成一行，避免最常见的迭代产生「标题 + 子节点」两行
  if (round.nodes.length === 1 && !only.tool.parallel) {
    return (
      <Leaf
        tool={only.tool}
        extra={only.extra}
        index={only.index}
        badge={`R${round.round}`}
        timeLabel={toolTimeLabel(only.tool, now)}
        active={activeId === only.tool.id}
        onFocus={onFocus}
      />
    )
  }
  const parallelCount = round.segments.reduce(
    (total, segment) =>
      total + (segment.parallel && segment.nodes.length > 1 ? segment.nodes.length : 0),
    0
  )
  return (
    <div className={styles.round}>
      <div className={styles.roundHead}>
        <span className={clsx(styles.dot, DOT_CLASS[round.status])} />
        <span className={styles.roundBadge}>R{round.round}</span>
        {parallelCount > 0 ? <span className={styles.parallelTag}>并行</span> : null}
        <span className={styles.roundMeta}>{round.nodes.length} 工具</span>
        <span className={styles.time}>{roundTimeLabel(round)}</span>
      </div>
      <div className={styles.roundBody}>
        {round.segments.map((segment) =>
          segment.parallel && segment.nodes.length > 1 ? (
            <div key={segment.key} className={styles.parallelGroup}>
              <div className={styles.parallelHead}>并行 ×{segment.nodes.length}</div>
              <div className={styles.parallelNodes}>
                {segment.nodes.map((node) => (
                  <Leaf
                    key={node.tool.id}
                    tool={node.tool}
                    extra={node.extra}
                    index={node.index}
                    badge=""
                    timeLabel={toolTimeLabel(node.tool, now)}
                    active={activeId === node.tool.id}
                    onFocus={onFocus}
                  />
                ))}
              </div>
            </div>
          ) : (
            segment.nodes.map((node) => (
              <Leaf
                key={node.tool.id}
                tool={node.tool}
                extra={node.extra}
                index={node.index}
                badge={`#${node.index}`}
                timeLabel={toolTimeLabel(node.tool, now)}
                active={activeId === node.tool.id}
                onFocus={onFocus}
              />
            ))
          )
        )}
      </div>
    </div>
  )
})

/**
 * Agent 迭代树：固定在消息列表上方，默认展开、可折叠。
 * 读当前会话可见分支里的 agent_tool 块，按 round 分组、同 round 内按 parallelGroup 分组；
 * 旧数据没有 round 时按顺序步骤展示。点击节点会滚动并高亮对应工具卡。
 */
export const AgentIterationTree = ({ session }: { session: Session }) => {
  const isMobile = useIsMobile()
  const [open, setOpen] = useState(() => !isMobile)
  const [activeId, setActiveId] = useState<string | null>(null)
  const bodyId = useId()
  const bodyRef = useRef<HTMLDivElement>(null)
  const stickRef = useRef(true)
  const flashTimer = useRef<number | null>(null)

  const model = useMemo(() => buildAgentTree(session), [session])
  const runningCount = model.running.length
  const now = useLiveNow(open && runningCount > 0)

  useEffect(
    () => () => {
      if (flashTimer.current !== null) {
        window.clearTimeout(flashTimer.current)
      }
    },
    []
  )

  const focusTool = useCallback((toolId: string) => {
    setActiveId(toolId)
    if (flashTimer.current !== null) {
      window.clearTimeout(flashTimer.current)
    }
    flashTimer.current = window.setTimeout(() => {
      setActiveId(null)
      flashTimer.current = null
    }, 1600)
    const card = document.querySelector<HTMLElement>(`[data-agent-tool-id="${CSS.escape(toolId)}"]`)
    if (!card) {
      return
    }
    card.setAttribute('data-agent-tool-flash', '')
    card.scrollIntoView({ block: 'center', behavior: 'smooth' })
    window.setTimeout(() => card.removeAttribute('data-agent-tool-flash'), 1400)
  }, [])

  const onBodyScroll = useCallback(() => {
    const body = bodyRef.current
    if (!body) {
      return
    }
    stickRef.current = body.scrollHeight - body.scrollTop - body.clientHeight < 24
  }, [])

  // 展开或新增工具时跟随底部；用户向上翻看后不再抢占滚动位置
  useLayoutEffect(() => {
    const body = bodyRef.current
    if (!open || !body || !stickRef.current) {
      return
    }
    body.scrollTop = body.scrollHeight
  }, [open, model.steps.length])

  if (model.steps.length === 0) {
    return null
  }

  const waiting = model.waiting.at(-1)
  const running = model.running.at(-1)
  const activity = waiting
    ? {
        tone: 'waiting',
        text:
          model.waiting.length > 1
            ? `${model.waiting.length} 个待确认`
            : `待确认 · ${waiting.tool.name}`,
      }
    : running
      ? {
          tone: 'running',
          text:
            model.running.length > 1
              ? `${model.running.length} 个并行运行中`
              : `运行中 · ${running.tool.name}`,
        }
      : null

  return (
    <section className={styles.panel} aria-label="迭代树">
      <button
        type="button"
        className={styles.bar}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => {
          setOpen((value) => {
            if (!value) {
              stickRef.current = true
            }
            return !value
          })
        }}
      >
        <span className={styles.glyph} aria-hidden="true">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M3.5 2.5v9M3.5 4.5h4M3.5 9.5h4" stroke="currentColor" strokeWidth="1" />
            <circle cx="9.5" cy="4.5" r="1.5" fill="currentColor" />
            <circle cx="9.5" cy="9.5" r="1.5" fill="currentColor" />
          </svg>
        </span>
        <span className={styles.title}>迭代树</span>
        <span className={styles.summary}>
          {model.roundMode
            ? `${model.rounds.length} 轮 · ${model.steps.length} 工具`
            : `${model.steps.length} 步`}
        </span>
        {model.errors > 0 ? (
          <span className={styles.errorSummary}>{model.errors} 失败</span>
        ) : null}
        {model.elapsedMs > 0 ? (
          <span className={styles.summary}>{formatDuration(model.elapsedMs)}</span>
        ) : null}
        <span className={styles.spacer} />
        {activity ? (
          <span
            className={clsx(
              styles.activity,
              activity.tone === 'waiting' ? styles.activityWaiting : styles.activityRunning
            )}
            title={activity.text}
          >
            <span className={styles.pulse} />
            <span className={styles.activityText}>{activity.text}</span>
          </span>
        ) : null}
        <span className={styles.chevron}>
          {open ? <IconChevronDownOutline14 /> : <IconChevronRightOutline14 />}
        </span>
      </button>
      {open ? (
        <div id={bodyId} ref={bodyRef} className={styles.body} onScroll={onBodyScroll}>
          <div className={styles.tree}>
            {model.roundMode
              ? model.rounds.map((round) => (
                  <div className={styles.entry} key={round.key}>
                    <RoundBlock round={round} now={now} activeId={activeId} onFocus={focusTool} />
                  </div>
                ))
              : model.steps.map((node: ToolNode) => (
                  <div className={styles.entry} key={node.tool.id}>
                    <Leaf
                      tool={node.tool}
                      extra={node.extra}
                      index={node.index}
                      badge={`#${node.index}`}
                      timeLabel={toolTimeLabel(node.tool, now)}
                      active={activeId === node.tool.id}
                      onFocus={focusTool}
                    />
                  </div>
                ))}
          </div>
        </div>
      ) : null}
    </section>
  )
}
