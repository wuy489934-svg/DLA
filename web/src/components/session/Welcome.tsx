import clsx from 'clsx'
import { useIsMobile } from '../../app/viewport.js'
import { useTranslate } from '../../i18n/index.js'
import { isAgentAvailable } from '../../services/agent/bridge.js'
import { setComposerMode, useComposer } from '../../services/composer.js'
import { playTap } from '../../services/sound.js'
import { ChatInput } from '../chatInput/ChatInput.js'
import { MobileHeader } from './MobileHeader.js'
import styles from './Welcome.module.css'

const agentReady = isAgentAvailable()

/** 新对话：先选 LLM / Agent，再输入。两区视觉刻意区分：LLM 清爽，Agent 深色终端感。 */
export const Welcome = () => {
  const t = useTranslate()
  const isMobile = useIsMobile()
  const mode = useComposer((state) => state.mode)
  return (
    <div className={clsx(styles.container, mode === 'agent' && styles.containerAgent)}>
      {isMobile ? <MobileHeader /> : null}
      <div className={clsx(styles.welcome, mode === 'agent' && styles.welcomeAgent)}>
        <div className={styles.greeting}>
          {t(mode === 'agent' ? 'welcomeMessageAgent' : 'welcomeMessageChat250808')}
        </div>
        <div className={styles.modes} role="radiogroup" aria-label={t('modeSelectorLabel')}>
          <button
            type="button"
            role="radio"
            aria-checked={mode === 'llm'}
            className={clsx(styles.mode, styles.modeLlm, mode === 'llm' && styles.modeActive)}
            onClick={() => {
              playTap()
              setComposerMode('llm')
            }}
          >
            <span className={styles.modeTag}>LLM</span>
            <span className={styles.modeTitle}>{t('modeLlmTitle')}</span>
            <span className={styles.modeDesc}>{t('modeLlmDesc')}</span>
          </button>
          <button
            type="button"
            role="radio"
            aria-checked={mode === 'agent'}
            disabled={!agentReady}
            title={agentReady ? undefined : t('modeAgentUnavailable')}
            className={clsx(styles.mode, styles.modeAgent, mode === 'agent' && styles.modeActive)}
            onClick={() => {
              playTap()
              setComposerMode('agent')
            }}
          >
            <span className={styles.modeTag}>AGENT</span>
            <span className={styles.modeTitle}>{t('modeAgentTitle')}</span>
            <span className={styles.modeDesc}>
              {agentReady ? t('modeAgentDesc') : t('modeAgentUnavailable')}
            </span>
          </button>
        </div>
        <div className={styles.orb} aria-hidden="true">
          <span className={styles.orbRing} />
          <span className={styles.orbCore} />
          <span className={styles.orbLabel}>DLA</span>
        </div>
        <div className={styles.composer}>
          <ChatInput sessionId={null} />
        </div>
        {isMobile ? <div className={styles.caveat}>{t('contentCaveat')}</div> : null}
      </div>
    </div>
  )
}
