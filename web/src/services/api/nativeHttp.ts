/** Android 原生 HTTP：用于 Key 校验/模型列表/连接测试，绕开 WebView CORS。 */
export type NativeHttpResult = {
  ok: boolean
  status: number
  body: string
  error?: string
}

export const nativeHttp = (
  method: string,
  url: string,
  headers: Record<string, string>,
  body = ''
): NativeHttpResult | null => {
  const host = typeof window === 'undefined' ? undefined : window.AndroidHost
  if (!host?.httpRequest) {
    return null
  }
  try {
    const raw = host.httpRequest(method, url, JSON.stringify(headers), body)
    return JSON.parse(raw) as NativeHttpResult
  } catch (error) {
    return {
      ok: false,
      status: 0,
      body: '',
      error: error instanceof Error ? error.message : String(error),
    }
  }
}
