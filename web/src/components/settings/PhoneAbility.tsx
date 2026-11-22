import { useEffect, useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { setFullAccess, useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import styles from './Settings.module.css'

type ShizukuStatus = { running: boolean; granted: boolean; installed?: boolean; version?: string }

const readStatus = (): ShizukuStatus => {
  try {
    const raw = window.AndroidHost?.shizukuStatus?.()
    if (raw) {
      return JSON.parse(raw) as ShizukuStatus
    }
  } catch {
    // 忽略解析失败，按未就绪处理
  }
  return { running: false, granted: false }
}

/** 手机能力：Shizuku 状态、授权与 shell 自检 */
export const PhoneAbility = () => {
  const t = useTranslate()
  const [status, setStatus] = useState<ShizukuStatus>(() => readStatus())
  const [a11yReady, setA11yReady] = useState(
    () => window.AndroidHost?.accessibilityStatus?.() === 'ready'
  )
  const [busy, setBusy] = useState(false)
  const [shellResult, setShellResult] = useState('')
  const fullAccess = useSettings((state) => state.fullAccess)

  useEffect(() => {
    setStatus(readStatus())
    setA11yReady(window.AndroidHost?.accessibilityStatus?.() === 'ready')
    const timer = window.setInterval(() => {
      setStatus(readStatus())
      setA11yReady(window.AndroidHost?.accessibilityStatus?.() === 'ready')
    }, 2000)
    return () => window.clearInterval(timer)
  }, [])

  const label = status.granted
    ? t('shizukuStatusGranted')
    : status.running
      ? t('shizukuStatusRunning')
      : t('shizukuStatusStopped')

  return (
    <div className={styles.phoneAbility}>
      <div className={styles.cell}>
        <div className={styles.text}>
          <div className={styles.label}>{t('phoneAbilityTitle')}</div>
          <div className={styles.description}>{t('phoneAbilityDescription')}</div>
        </div>
        <span className={status.granted ? styles.phoneReady : styles.phonePending}>{label}</span>
      </div>
      <div className={styles.phoneActions}>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          onClick={() => window.AndroidHost?.openAccessibilitySettings?.()}
        >
          {a11yReady ? t('a11yEnabled') : t('a11yOpen')}
        </Button>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          onClick={() => window.AndroidHost?.exportDiagnostics?.()}
        >
          {t('diagnosticsExport')}
        </Button>
      </div>
      <div className={styles.phoneActions}>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          onClick={() => {
            if (status.installed) {
              window.AndroidHost?.shizukuOpen?.()
            } else {
              window.AndroidHost?.shizukuInstall?.()
            }
          }}
        >
          {status.installed ? t('shizukuOpen') : t('shizukuInstall')}
        </Button>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          disabled={!status.running || status.granted}
          onClick={() => window.AndroidHost?.shizukuRequest?.()}
        >
          {t('shizukuGrant')}
        </Button>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          disabled={!status.granted || busy}
          onClick={() => {
            setBusy(true)
            const result = window.AndroidHost?.shellTest?.('id') ?? ''
            setBusy(false)
            setShellResult(result.trim() || '❌ Shell 测试失败：没有返回结果')
          }}
        >
          {t('shizukuTest')}
        </Button>
      </div>
      {shellResult ? (
        <div
          className={`${styles.shellResult} ${
            shellResult.startsWith('✅') ? styles.shellSuccess : styles.shellError
          }`}
        >
          {shellResult}
        </div>
      ) : null}
      <div className={styles.phoneHint}>{t('shizukuHint')}</div>
      <div className={styles.phoneHint}>{t('shizukuPersistenceHint')}</div>
      <div className={styles.phoneHint}>
        {status.installed
          ? t('shizukuInstalledInfo', { version: status.version || '—' })
          : t('shizukuNotInstalledInfo')}
      </div>
      <label className={styles.mirrorToggle}>
        <input
          type="checkbox"
          checked={fullAccess}
          onChange={(event) => setFullAccess(event.target.checked)}
        />
        <span>{t('fullAccessLabel')}</span>
      </label>
      <div className={styles.phoneHint}>{t('fullAccessDescription')}</div>
    </div>
  )
}
