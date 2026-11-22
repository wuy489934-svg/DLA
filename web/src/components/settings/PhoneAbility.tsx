import { useEffect, useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { setFullAccess, useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import styles from './Settings.module.css'

type ShizukuStatus = { running: boolean; granted: boolean; installed?: boolean; version?: string }
type TermuxStatus = { installed: boolean; version?: string; granted?: boolean; error?: string }

const readTermuxStatus = (): TermuxStatus => {
  try {
    const raw = window.AndroidHost?.termuxStatus?.()
    if (raw) {
      return JSON.parse(raw) as TermuxStatus
    }
  } catch {
    // 忽略解析失败，按未安装处理
  }
  return { installed: false }
}

const TERMUX_ALLOW_COMMAND =
  'echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings'

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

/** 手机能力：Shizuku 状态、授权与 shell 自检；Termux 环境与一键安装 */
export const PhoneAbility = () => {
  const t = useTranslate()
  const [status, setStatus] = useState<ShizukuStatus>(() => readStatus())
  const [termux, setTermux] = useState<TermuxStatus>(() => readTermuxStatus())
  const [a11yReady, setA11yReady] = useState(
    () => window.AndroidHost?.accessibilityStatus?.() === 'ready'
  )
  const [busy, setBusy] = useState(false)
  const [shellResult, setShellResult] = useState('')
  const [termuxResult, setTermuxResult] = useState('')
  const [copied, setCopied] = useState(false)
  const fullAccess = useSettings((state) => state.fullAccess)

  useEffect(() => {
    const refresh = () => {
      setStatus(readStatus())
      setTermux(readTermuxStatus())
      setTermuxResult(window.AndroidHost?.termuxLastResult?.() ?? '')
      setA11yReady(window.AndroidHost?.accessibilityStatus?.() === 'ready')
    }
    refresh()
    const timer = window.setInterval(refresh, 2000)
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
      <div className={styles.cell}>
        <div className={styles.text}>
          <div className={styles.label}>{t('termuxTitle')}</div>
          <div className={styles.description}>{t('termuxDescription')}</div>
        </div>
        <span className={termux.installed ? styles.phoneReady : styles.phonePending}>
          {termux.installed
            ? termux.granted === false
              ? t('termuxPermissionMissing')
              : t('termuxInstalled', { version: termux.version || '—' })
            : t('termuxNotInstalled')}
        </span>
      </div>
      <div className={styles.phoneActions}>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          disabled={!termux.installed}
          onClick={() => window.AndroidHost?.termuxOpen?.()}
        >
          {t('termuxOpen')}
        </Button>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          disabled={!termux.installed || termux.granted === false}
          onClick={() => window.AndroidHost?.termuxSetup?.()}
        >
          {t('termuxSetup')}
        </Button>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          disabled={!termux.installed || termux.granted === false}
          onClick={() => window.AndroidHost?.termuxTest?.()}
        >
          {t('termuxTest')}
        </Button>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          onClick={() => window.AndroidHost?.termuxOpenSettings?.()}
        >
          {t('termuxPermission')}
        </Button>
      </div>
      <div className={styles.phoneActions}>
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          onClick={() => {
            void navigator.clipboard.writeText(TERMUX_ALLOW_COMMAND).then(() => {
              setCopied(true)
              window.setTimeout(() => setCopied(false), 2000)
            })
          }}
        >
          {copied ? t('termuxCopied') : t('termuxCopyCommand')}
        </Button>
      </div>
      <div className={styles.phoneHint}>{t('termuxHint')}</div>
      <div className={styles.phoneHint}>{t('termuxMirrorHint')}</div>
      <div className={styles.phoneHint}>{t('termuxHarnessHint')}</div>
      {termuxResult ? (
        <div className={styles.shellResult} style={{ whiteSpace: 'pre-wrap' }}>
          {termuxResult}
        </div>
      ) : null}
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
