import { useRef, useState } from 'react'
import clsx from 'clsx'
import { useTranslate } from '../../i18n/index.js'
import { setPersona, setPersonaImage, useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { showToast } from '../base/toastState.js'
import styles from './Settings.module.css'

/** 人设与立绘：只在 LLM 区作为 system 提示词与隐形首轮图片注入，Agent 区使用独立工具规则 */
export const PersonaSetting = () => {
  const t = useTranslate()
  const persona = useSettings((state) => state.persona)
  const image = useSettings((state) => state.personaImage)
  const inputRef = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState(false)

  const pick = (file: File) => {
    if (file.size > 1.5 * 1024 * 1024) {
      showToast({ key: 'personaImageTooLarge', type: 'error' })
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      setPersonaImage(String(reader.result ?? ''))
      setPreview(false)
    }
    reader.onerror = () => showToast({ key: 'personaImageFailed', type: 'error' })
    reader.readAsDataURL(file)
  }

  return (
    <div className={styles.persona}>
      <div className={styles.cell}>
        <div className={styles.text}>
          <div className={styles.label}>{t('personaLabel')}</div>
          <div className={styles.description}>{t('personaDescription')}</div>
        </div>
        <div className={styles.personaActions}>
          {image ? (
            <>
              <Button
                variant="outlined"
                size="m"
                className={styles.cellButton}
                onClick={() => setPreview((value) => !value)}
              >
                {t('personaPreviewImage')}
              </Button>
              <Button
                variant="outlined"
                size="m"
                className={styles.cellButton}
                onClick={() => setPersonaImage(null)}
              >
                {t('personaRemoveImage')}
              </Button>
            </>
          ) : (
            <Button
              variant="outlined"
              size="m"
              className={clsx(styles.cellButton, styles.uploadButton)}
              onClick={() => inputRef.current?.click()}
            >
              {t('personaUploadImage')}
            </Button>
          )}
          <input
            ref={inputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) {
                pick(file)
              }
            }}
          />
        </div>
      </div>
      {preview && image ? (
        <img className={styles.personaPreview} src={image} alt={t('personaLabel')} />
      ) : null}
      <textarea
        className={styles.personaTextarea}
        value={persona}
        rows={6}
        placeholder={t('personaPlaceholder')}
        onChange={(event) => setPersona(event.target.value)}
      />
      <div className={styles.personaHint}>{t('personaOnlyLlm')}</div>
    </div>
  )
}
