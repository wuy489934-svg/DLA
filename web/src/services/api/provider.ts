/** DLA 只提供 DeepSeek 兼容协议。 */

export type ApiProviderId = 'deepseek'
export type ApiProtocol = 'anthropic'

export type ApiConfig = {
  provider: ApiProviderId
  protocol: ApiProtocol
  baseUrl: string
  model: string
  apiKey: string
}

export type ProviderPreset = {
  protocol: ApiProtocol
  baseUrl: string
  model: string
}

const deepseekBase = `${__API_ORIGIN__}/anthropic/v1`

export const PROVIDER_PRESETS: Record<ApiProviderId, ProviderPreset> = {
  deepseek: { protocol: 'anthropic', baseUrl: deepseekBase, model: 'deepseek-flash' },
}

export const isProviderId = (value: string | null): value is ApiProviderId =>
  value === 'deepseek'

export const isProtocol = (value: string | null): value is ApiProtocol =>
  value === 'anthropic'

const trimBase = (value: string): string => value.trim().replace(/\/+$/, '')

/** Base URL/模型留空时回退到 DeepSeek 默认值；不再读取其它 provider。 */
export const getApiConfig = (
  settings: {
    apiBaseUrl?: string
    apiModel?: string
    apiKey?: string | null
  },
  apiKeyOverride?: string
): ApiConfig => {
  const preset = PROVIDER_PRESETS.deepseek
  return {
    provider: 'deepseek',
    protocol: 'anthropic',
    baseUrl: trimBase(settings.apiBaseUrl ?? '') || preset.baseUrl,
    model: (settings.apiModel ?? '').trim() || preset.model,
    apiKey: apiKeyOverride ?? settings.apiKey ?? '',
  }
}

export const supportsDeepseekFeatures = (_provider: ApiProviderId): boolean => true
