import { API_ORIGIN, nativeFailure, request } from './client.js'
import { nativeHttp } from './nativeHttp.js'
import type { ApiConfig } from './provider.js'

const authHeaders = (config: ApiConfig): Record<string, string> => ({
  Authorization: `Bearer ${config.apiKey}`,
})

/** 拉取 DeepSeek 模型列表；不产生生成费用。 */
export const listModels = async (config: ApiConfig, signal?: AbortSignal): Promise<string[]> => {
  const url = `${API_ORIGIN}/models`
  const native = nativeHttp('GET', url, authHeaders(config))
  if (native) {
    if (!native.ok) {
      throw nativeFailure(native)
    }
    try {
      const body = JSON.parse(native.body) as { data?: { id?: unknown }[] }
      return [
        ...new Set(
          (body.data ?? [])
            .map((item) => (typeof item.id === 'string' ? item.id.trim() : ''))
            .filter(Boolean)
        ),
      ]
    } catch {
      return []
    }
  }
  const response = await request(url, { method: 'GET', headers: authHeaders(config), signal })
  const body = (await response.json()) as { data?: { id?: unknown }[] }
  return [
    ...new Set(
      (body.data ?? [])
        .map((item) => (typeof item.id === 'string' ? item.id.trim() : ''))
        .filter(Boolean)
    ),
  ]
}

/** 用最小非流式请求验证 DeepSeek Key 和模型。 */
export const testModelConnection = async (
  config: ApiConfig,
  model: string,
  signal?: AbortSignal
): Promise<void> => {
  const url = `${config.baseUrl}/messages`
  const headers = {
    ...authHeaders(config),
    'Content-Type': 'application/json',
  }
  const body = JSON.stringify({
    model,
    max_tokens: 1,
    messages: [{ role: 'user', content: 'ping' }],
  })
  const native = nativeHttp('POST', url, headers, body)
  if (native) {
    if (!native.ok) {
      throw nativeFailure(native)
    }
    return
  }
  await request(url, { method: 'POST', headers, body, signal })
}
