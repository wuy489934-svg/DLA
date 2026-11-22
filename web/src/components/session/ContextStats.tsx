import clsx from 'clsx'
import { useTranslate } from '../../i18n/index.js'
import type { Session } from '../../services/chat/types.js'
import { getContextPercent } from '../../services/chat/context.js'
import { compactContext, useChatStore } from '../../services/chat/store.js'
import {
  cacheHitPercent,
  formatTokenCount,
  getSessionUsageStats,
} from '../../services/usageStats.js'
import styles from '../session/SessionView.module.css'

/** Agent：完整统计；LLM：只显示上下文占用和压缩按钮 */
export const ContextStats = ({ session }: { session: Session }) => {
  const t = useTranslate()
  const stats = getSessionUsageStats(session)
  const streaming = useChatStore((state) => Boolean(state.streaming[session.id]))
  const compacting = useChatStore((state) => Boolean(state.compacting[session.id]))
  const percent = getContextPercent(session)
  const percentLabel = percent < 10 ? percent.toFixed(1) : String(percent)
  const hit = cacheHitPercent(stats)
  const ttft =
    stats.lastTtftMs > 0
      ? stats.lastTtftMs >= 1000
        ? `${(stats.lastTtftMs / 1000).toFixed(1)}s`
        : `${Math.round(stats.lastTtftMs)}ms`
      : '—'
  const canCompact = !streaming && !compacting && percent > 20
  const compactBlocked = !streaming && !compacting && percent <= 20
  const button = (
    <button
      type="button"
      className={clsx(styles.contextButton, compacting && styles.contextButtonBusy)}
      title={
        compactBlocked
          ? t('contextCompactNotNeededHint', { percent: percentLabel })
          : t('contextCompactHint')
      }
      disabled={!canCompact}
      onClick={() => void compactContext(session.id)}
    >
      {compacting
        ? t('contextCompactRunning')
        : compactBlocked
          ? t('contextCompactNotNeeded')
          : t('contextCompact')}
    </button>
  )

  if (session.mode !== 'agent') {
    return (
      <div className={clsx(styles.statsBar, styles.llmStatsBar)}>
        <span className={styles.llmContext}>
          {t('contextUsageOnly', { percent: percentLabel })}
        </span>
        <span className={styles.statsSpacer} />
        {button}
      </div>
    )
  }

  return (
    <div className={styles.statsBar}>
      <span className={styles.statsGroup}>
        {t('contextStatsRounds', {
          rounds: String(stats.rounds),
          steps: String(stats.steps),
          tps: stats.lastTps > 0 ? stats.lastTps.toFixed(0) : '—',
          ttft,
        })}
      </span>
      <span className={styles.statsGroup}>
        {t('contextStatsTokens', {
          tokens: formatTokenCount(
            stats.inputTokens +
              stats.cacheCreationTokens +
              stats.cacheReadTokens +
              stats.outputTokens
          ),
          hit: String(hit),
        })}
      </span>
      <span className={styles.statsSpacer} />
      {button}
      <span
        className={clsx(styles.contextPercent, percent >= 65 && styles.contextWarn)}
        title={t('contextPercentHint', { percent: percentLabel })}
      >
        {percentLabel}%
      </span>
    </div>
  )
}
