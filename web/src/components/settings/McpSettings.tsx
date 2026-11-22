import { useState } from 'react'
import clsx from 'clsx'
import { useTranslate } from '../../i18n/index.js'
import {
  deleteMcpServer,
  isAgentAvailable,
  listMcpServers,
  saveMcpServer,
  testMcpServer,
  type McpServer,
} from '../../services/agent/bridge.js'
import { Button } from '../base/Button.js'
import { showToast } from '../base/toastState.js'
import styles from './Settings.module.css'

const emptyDraft = { name: '', url: '', token: '', enabled: true }

/** Agent 区的 MCP Server 管理：配置由原生保存，浏览器里只读提示 */
export const McpSettings = () => {
  const t = useTranslate()
  const available = isAgentAvailable()
  const [servers, setServers] = useState<McpServer[]>(() => listMcpServers())
  const [draft, setDraft] = useState(emptyDraft)
  const [busy, setBusy] = useState(false)

  const refresh = () => setServers(listMcpServers())

  const add = () => {
    if (!draft.url.trim()) {
      showToast({ key: 'mcpUrlRequired', type: 'error' })
      return
    }
    saveMcpServer({
      name: draft.name.trim() || draft.url.trim(),
      url: draft.url.trim(),
      token: draft.token.trim(),
      enabled: draft.enabled,
    })
    setDraft(emptyDraft)
    refresh()
    showToast({ key: 'mcpSaved' })
  }

  const test = async (server: McpServer) => {
    setBusy(true)
    const result = await testMcpServer(server)
    setBusy(false)
    if (result.ok) {
      showToast({ key: 'mcpTestOk', params: { count: result.tools ?? 0 } })
    } else {
      showToast({ key: 'mcpTestFailed', params: { message: result.message ?? '' }, type: 'error' })
    }
  }

  return (
    <div className={styles.mcp}>
      <div className={styles.mcpIntro}>{t(available ? 'mcpDescription' : 'mcpNeedApp')}</div>
      {servers.length === 0 ? <div className={styles.mcpEmpty}>{t('mcpEmpty')}</div> : null}
      {servers.map((server) => (
        <div key={server.id} className={styles.mcpRow}>
          <label className={styles.mcpToggle}>
            <input
              type="checkbox"
              checked={server.enabled}
              onChange={(event) => {
                saveMcpServer({ ...server, enabled: event.target.checked })
                refresh()
              }}
            />
            <span className={styles.mcpName}>{server.name}</span>
          </label>
          <span className={styles.mcpUrl} title={server.url}>
            {server.url}
          </span>
          <div className={styles.mcpActions}>
            <button
              type="button"
              className={styles.mcpLink}
              disabled={busy || !available}
              onClick={() => void test(server)}
            >
              {t('mcpTest')}
            </button>
            <button
              type="button"
              className={clsx(styles.mcpLink, styles.mcpDanger)}
              onClick={() => {
                deleteMcpServer(server.id)
                refresh()
              }}
            >
              {t('delete')}
            </button>
          </div>
        </div>
      ))}

      <div className={styles.mcpForm}>
        <input
          className={styles.mcpInput}
          value={draft.name}
          placeholder={t('mcpName')}
          onChange={(event) => setDraft({ ...draft, name: event.target.value })}
        />
        <input
          className={styles.mcpInput}
          value={draft.url}
          placeholder={t('mcpUrlPlaceholder')}
          onChange={(event) => setDraft({ ...draft, url: event.target.value })}
        />
        <input
          className={styles.mcpInput}
          value={draft.token}
          placeholder={t('mcpToken')}
          onChange={(event) => setDraft({ ...draft, token: event.target.value })}
        />
        <Button
          variant="outlined"
          size="m"
          className={styles.cellButton}
          disabled={!available}
          onClick={add}
        >
          {t('mcpAdd')}
        </Button>
      </div>
    </div>
  )
}
