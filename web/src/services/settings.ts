import { create } from 'zustand'
import type { TtsVoice } from './api/speech.js'
import {
  isProtocol,
  isProviderId,
  PROVIDER_PRESETS,
  type ApiProtocol,
  type ApiProviderId,
} from './api/provider.js'
import { normalizeLocation, type UserLocation } from './location.js'

export type Locale = 'zh-CN' | 'en-US'

/** 主题偏好；默认跟随系统 */
export type ThemePreference = 'light' | 'dark' | 'system'

/** 本机设置，存 localStorage：API Key、语言、主题、位置、朗读音色、深度思考与智能搜索开关、侧边栏收起状态、LLM 区人设 */
type Settings = {
  apiKey: string | null
  locale: Locale
  themePreference: ThemePreference
  location: UserLocation | null
  ttsVoice: TtsVoice
  thinking: boolean
  search: boolean
  siderCollapsed: boolean
  /** LLM 区的系统提示词；为空时使用内置助手身份 */
  persona: string
  /** LLM 区的立绘 data URL；仅在 LLM 区作为隐形首轮图片注入 */
  personaImage: string | null
  /** 界面音效开关 */
  soundEnabled: boolean
  /** 完全权限：Agent 工具不再逐次确认，直接执行 */
  fullAccess: boolean
  /** 模型服务：DeepSeek / OpenAI / Anthropic / 自定义 */
  apiProvider: ApiProviderId
  /** 自定义服务的协议；预设服务由 provider 决定 */
  apiProtocol: ApiProtocol
  /** 空字符串表示使用当前 provider 的默认 base URL */
  apiBaseUrl: string
  /** 空字符串表示使用当前 provider 的默认模型 */
  apiModel: string
}

const KEYS = {
  apiKey: 'chat-demo:api-key',
  locale: 'chat-demo:locale',
  location: 'chat-demo:location',
  themePreference: 'chat-demo:theme-preference',
  ttsVoice: 'chat-demo:tts-voice',
  thinking: 'chat-demo:thinking',
  search: 'chat-demo:search',
  siderCollapsed: 'chat-demo:sider-collapsed',
  persona: 'chat-demo:persona',
  personaImage: 'chat-demo:persona-image',
  soundEnabled: 'chat-demo:sound-enabled',
  fullAccess: 'chat-demo:full-access',
  apiProvider: 'chat-demo:api-provider',
  apiProtocol: 'chat-demo:api-protocol',
  apiBaseUrl: 'chat-demo:api-base-url',
  apiModel: 'chat-demo:api-model',
} as const

const read = (key: string) => {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

const write = (key: string, value: string | null) => {
  try {
    if (value === null) {
      localStorage.removeItem(key)
    } else {
      localStorage.setItem(key, value)
    }
  } catch {
    // 存储不可用时只在内存中生效
  }
}

/** 位置选择模式跟随浏览器语言，与应用内选择的界面语言无关。 */
export const isChineseBrowser = (): boolean =>
  (typeof navigator === 'undefined' ? 'zh' : navigator.language).toLowerCase().startsWith('zh')

const initialLocale = (): Locale => {
  const saved = read(KEYS.locale)
  if (saved === 'zh-CN' || saved === 'en-US') {
    return saved
  }
  return isChineseBrowser() ? 'zh-CN' : 'en-US'
}

const initialLocation = (): UserLocation | null => {
  try {
    const location = normalizeLocation(JSON.parse(read(KEYS.location) ?? 'null'))
    if (!location) {
      return null
    }
    return isChineseBrowser()
      ? location.region === 'CN' && location.province
        ? location
        : null
      : { region: location.region }
  } catch {
    return null
  }
}

/** 未保存过时跟随系统 */
const initialThemePreference = (): ThemePreference => {
  const saved = read(KEYS.themePreference)
  return saved === 'light' || saved === 'dark' ? saved : 'system'
}

/** 未保存过时使用服务端默认音色 stella（海星） */
const initialTtsVoice = (): TtsVoice => (read(KEYS.ttsVoice) === 'tide' ? 'tide' : 'stella')

const initialApiProvider = (): ApiProviderId => {
  const saved = read(KEYS.apiProvider)
  return isProviderId(saved) ? saved : 'deepseek'
}

const initialApiProtocol = (): ApiProtocol => {
  const saved = read(KEYS.apiProtocol)
  return isProtocol(saved) ? saved : 'anthropic'
}

const initialApiBaseUrl = (): string => {
  const saved = read(KEYS.apiBaseUrl) ?? ''
  const provider = initialApiProvider()
  if (saved === PROVIDER_PRESETS[provider].baseUrl) {
    write(KEYS.apiBaseUrl, null)
    return ''
  }
  return saved
}

const initialApiModel = (): string => {
  const saved = read(KEYS.apiModel) ?? ''
  const provider = initialApiProvider()
  if (saved === PROVIDER_PRESETS[provider].model) {
    write(KEYS.apiModel, null)
    return ''
  }
  return saved
}

const initialApiKey = (): string | null => {
  const saved = read(KEYS.apiKey)
  // 清掉开发/联调时写入的占位 Key，避免用户看到“Authentication Fails: ****-key”。
  if (saved === 'mock-key') {
    write(KEYS.apiKey, null)
    return null
  }
  return saved
}

export const useSettings = create<Settings>(() => ({
  apiKey: initialApiKey(),
  locale: initialLocale(),
  location: initialLocation(),
  themePreference: initialThemePreference(),
  ttsVoice: initialTtsVoice(),
  thinking: read(KEYS.thinking) === 'true',
  search: read(KEYS.search) === 'true',
  siderCollapsed: read(KEYS.siderCollapsed) === 'true',
  persona: read(KEYS.persona) ?? '',
  personaImage: read(KEYS.personaImage),
  soundEnabled: read(KEYS.soundEnabled) !== 'false',
  fullAccess: read(KEYS.fullAccess) === 'true',
  apiProvider: initialApiProvider(),
  apiProtocol: initialApiProtocol(),
  apiBaseUrl: initialApiBaseUrl(),
  apiModel: initialApiModel(),
}))

export const setLocale = (locale: Locale) => {
  write(KEYS.locale, locale)
  useSettings.setState({ locale })
}

export const setLocation = (value: UserLocation | null) => {
  const location = normalizeLocation(value)
  write(KEYS.location, location ? JSON.stringify(location) : null)
  useSettings.setState({ location })
}

export const setThemePreference = (themePreference: ThemePreference) => {
  write(KEYS.themePreference, themePreference)
  useSettings.setState({ themePreference })
}

export const setTtsVoice = (ttsVoice: TtsVoice) => {
  write(KEYS.ttsVoice, ttsVoice)
  useSettings.setState({ ttsVoice })
}

export const setApiKey = (apiKey: string | null) => {
  write(KEYS.apiKey, apiKey)
  useSettings.setState({ apiKey })
}

export const setThinking = (thinking: boolean) => {
  write(KEYS.thinking, String(thinking))
  useSettings.setState({ thinking })
}

export const setSearch = (search: boolean) => {
  write(KEYS.search, String(search))
  useSettings.setState({ search })
}

export const setSiderCollapsed = (siderCollapsed: boolean) => {
  write(KEYS.siderCollapsed, String(siderCollapsed))
  useSettings.setState({ siderCollapsed })
}

export const setPersona = (persona: string) => {
  write(KEYS.persona, persona || null)
  useSettings.setState({ persona })
}

export const setPersonaImage = (personaImage: string | null) => {
  write(KEYS.personaImage, personaImage)
  useSettings.setState({ personaImage })
}

export const setSoundEnabled = (soundEnabled: boolean) => {
  write(KEYS.soundEnabled, String(soundEnabled))
  useSettings.setState({ soundEnabled })
}

export const setFullAccess = (fullAccess: boolean) => {
  write(KEYS.fullAccess, String(fullAccess))
  useSettings.setState({ fullAccess })
}

export const setApiProvider = (apiProvider: ApiProviderId) => {
  const preset = PROVIDER_PRESETS[apiProvider]
  const apiProtocol = preset.protocol
  // Base URL / 模型留空，输入框显示 placeholder；这样用户可以随时清空并手填。
  const apiBaseUrl = ''
  const apiModel = ''
  write(KEYS.apiProvider, apiProvider)
  write(KEYS.apiProtocol, apiProtocol)
  write(KEYS.apiBaseUrl, null)
  write(KEYS.apiModel, null)
  if (apiProvider !== 'deepseek') {
    write(KEYS.thinking, 'false')
    write(KEYS.search, 'false')
    useSettings.setState({ apiProvider, apiProtocol, apiBaseUrl, apiModel, thinking: false, search: false })
  } else {
    useSettings.setState({ apiProvider, apiProtocol, apiBaseUrl, apiModel })
  }
}

export const setApiProtocol = (apiProtocol: ApiProtocol) => {
  write(KEYS.apiProtocol, apiProtocol)
  useSettings.setState({ apiProtocol })
}

export const setApiBaseUrl = (apiBaseUrl: string) => {
  write(KEYS.apiBaseUrl, apiBaseUrl.trim() || null)
  useSettings.setState({ apiBaseUrl: apiBaseUrl.trim() })
}

export const setApiModel = (apiModel: string) => {
  write(KEYS.apiModel, apiModel.trim() || null)
  useSettings.setState({ apiModel: apiModel.trim() })
}
