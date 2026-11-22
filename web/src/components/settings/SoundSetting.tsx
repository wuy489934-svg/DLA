import { useTranslate } from '../../i18n/index.js'
import { setSoundEnabled, useSettings } from '../../services/settings.js'
import styles from './Settings.module.css'

/** 界面音效开关 */
export const SoundSetting = () => {
  const t = useTranslate()
  const enabled = useSettings((state) => state.soundEnabled)
  return (
    <label className={styles.mirrorToggle}>
      <input
        type="checkbox"
        checked={enabled}
        onChange={(event) => setSoundEnabled(event.target.checked)}
      />
      <span>{t('soundLabel')}</span>
    </label>
  )
}
