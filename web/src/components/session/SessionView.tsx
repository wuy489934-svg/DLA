import clsx from 'clsx'
import { useIsMobile } from '../../app/viewport.js'
import { useTranslate } from '../../i18n/index.js'
import type { Session } from '../../services/chat/types.js'
import { ChatInput } from '../chatInput/ChatInput.js'
import { MessageList } from '../messages/MessageList.js'
import { AgentIterationTree } from './AgentIterationTree.js'
import { MobileHeader } from './MobileHeader.js'
import { ContextStats } from './ContextStats.js'
import styles from './SessionView.module.css'

/** 会话页：标题栏（移动端为顶栏）+ 消息列表，输入框吸底 */
export const SessionView = ({
  session,
  streaming,
  siderCollapsed,
}: {
  session: Session
  streaming: boolean
  siderCollapsed: boolean
}) => {
  const t = useTranslate()
  const isMobile = useIsMobile()
  const title = session.title || t('untitledChat')
  const agent = session.mode === 'agent'
  return (
    <div className={clsx(styles.view, agent && styles.agentView)}>
      {isMobile ? (
        <MobileHeader title={title} />
      ) : (
        <div className={styles.header}>
          <div className={clsx(styles.title, siderCollapsed && styles.withCollapsedSider)}>
            {agent ? <span className={styles.agentBadge}>AGENT</span> : null}
            <span className={styles.titleText}>{title}</span>
          </div>
        </div>
      )}
      {agent ? <AgentIterationTree key={session.id} session={session} /> : null}
      <MessageList
        session={session}
        streaming={streaming}
        footer={
          <div className={styles.inputContainer}>
            <ContextStats session={session} />
            <ChatInput sessionId={session.id} />
            <div className={styles.caveat}>{t('contentCaveat')}</div>
          </div>
        }
      />
    </div>
  )
}
