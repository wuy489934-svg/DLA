import { useTranslate } from '../../i18n/index.js'
import { useState } from 'react'
import { getApiConfig, PROVIDER_PRESETS } from '../../services/api/provider.js'
import { listModels, testModelConnection } from '../../services/api/models.js'
import {
  setApiBaseUrl,
  setApiModel,
  useSettings,
} from '../../services/settings.js'
import { showToast } from '../base/toastState.js'
import { Button } from '../base/Button.js'
import styles from './Settings.module.css'

/** DLA 仅保留 DeepSeek 兼容协议；Base URL/模型留空时在内部使用 DeepSeek 默认值。 */
export const ModelSettings = () => {
  const t = useTranslate()
  const baseUrl = useSettings((state) => state.apiBaseUrl)
  const model = useSettings((state) => state.apiModel)
  const apiKey = useSettings((state) => state.apiKey)
  const [models, setModels] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<{ text: string; error: boolean } | null>(null)
  const preset = PROVIDER_PRESETS.deepseek

  const fetchAndTest = async () => {
    if (!apiKey) {
      setStatus({ text: t('modelNeedApiKey'), error: true })
      return
    }
    setBusy(true)
    setStatus({ text: t('modelTesting'), error: false })
    try {
      const config = getApiConfig(useSettings.getState())
      let list: string[] = []
      try {
        list = await listModels(config)
      } catch {
        // DeepSeek 理论上提供 /models；网络/兼容代理异常时仍尝试对话测试。
      }
      setModels(list)
      const target = model.trim() || list[0] || preset.model
      await testModelConnection(config, target)
      setApiModel(target)
      const text =
        list.length > 0
          ? t('modelTestOk', { model: target, count: String(list.length) })
          : t('modelTestOkNoList', { model: target })
      setStatus({ text, error: false })
      showToast({ key: list.length > 0 ? 'modelTestOk' : 'modelTestOkNoList', params: { model: target, count: String(list.length) } })
    } catch (error) {
      setStatus({
        text: t('modelTestFailed', {
          message: error instanceof Error ? error.message : String(error),
        }),
        error: true,
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.modelBlock}>
      <div className={styles.modelTitle}>{t('modelDeepSeekTitle')}</div>
      <div className={styles.modelGrid}>
        <label className={styles.modelField}>
          <span className={styles.modelLabel}>{t('modelBaseUrl')}</span>
          <input
            className={styles.modelInput}
            value={baseUrl}
            spellCheck={false}
            placeholder={t('modelBaseUrlPlaceholder')}
            onChange={(event) => setApiBaseUrl(event.target.value)}
          />
        </label>
        <label className={styles.modelField}>
          <span className={styles.modelLabel}>{t('modelName')}</span>
          <input
            className={styles.modelInput}
            value={model}
            spellCheck={false}
            placeholder={t('modelNamePlaceholder')}
            onChange={(event) => setApiModel(event.target.value)}
          />
          {models.length > 0 ? (
            <select
              className={styles.modelSelect}
              value={models.includes(model) ? model : ''}
              aria-label={t('modelFetchedModels')}
              onChange={(event) => setApiModel(event.target.value)}
            >
              <option value="">{t('modelFetchedModels', { count: String(models.length) })}</option>
              {models.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          ) : null}
        </label>
      </div>
      <div className={styles.modelActions}>
        <Button
          variant="outlined"
          size="m"
          disabled={busy || !apiKey}
          onClick={() => void fetchAndTest()}
        >
          {busy ? t('modelTesting') : t('modelFetchTest')}
        </Button>
        {status ? (
          <span className={status.error ? styles.modelStatusError : styles.modelStatusOk}>
            {status.text}
          </span>
        ) : null}
      </div>
      <div className={styles.modelHint}>{t('modelDeepSeekHint')}</div>
      <div className={styles.modelHint}>{t('modelApiKeyHint')}</div>
    </div>
  )
}
