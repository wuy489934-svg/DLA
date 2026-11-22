import { useEffect, useState } from 'react'
import { ApiError, verifyApiKey } from '../../services/api/client.js'
import { getApiConfig } from '../../services/api/provider.js'
import { classifyApiError } from '../../services/api/errors.js'
import { setApiKey, useSettings } from '../../services/settings.js'
import { useTranslate } from '../../i18n/index.js'

/** 输入 API Key 后用最小对话请求校验；部分兼容服务没有 /models。 */
export const useApiKeyForm = (onSaved?: () => void) => {
  const t = useTranslate()
  const [value, setValue] = useState('')
  const [verifying, setVerifying] = useState(false)
  const [failure, setFailure] = useState<{ cause: unknown } | null>(null)
  const provider = useSettings((state) => state.apiProvider)
  const protocol = useSettings((state) => state.apiProtocol)
  const baseUrl = useSettings((state) => state.apiBaseUrl)
  const model = useSettings((state) => state.apiModel)

  // 切换服务/地址/模型后，上一条 provider 的错误不能继续贴在表单上。
  useEffect(() => {
    setFailure(null)
  }, [provider, protocol, baseUrl, model])

  const cause = failure?.cause
  const error =
    failure === null
      ? null
      : classifyApiError(cause) === 'unauthorized'
        ? t('apiKeyInvalid')
        : classifyApiError(cause) === 'network'
          ? t('commonNetworkErrorToast')
          : t('apiKeyVerificationFailed', {
              message:
                cause instanceof ApiError && cause.localMessage
                  ? t(cause.localMessage.key, cause.localMessage.params)
                  : cause instanceof Error
                    ? cause.message
                    : String(cause),
            })

  const submit = async () => {
    const apiKey = value.trim()
    if (!apiKey || verifying) {
      return
    }
    setVerifying(true)
    setFailure(null)
    try {
      const config = getApiConfig(useSettings.getState(), apiKey)
      try {
        await verifyApiKey(apiKey, config)
      } catch (error) {
        // 移动网络切换/瞬时断流时重试一次，避免把临时网络抖动当成 Key 无效。
        if (classifyApiError(error) !== 'network') {
          throw error
        }
        await new Promise((resolve) => window.setTimeout(resolve, 700))
        await verifyApiKey(apiKey, config)
      }
      setApiKey(apiKey)
      setValue('')
      onSaved?.()
    } catch (e) {
      setFailure({ cause: e })
    } finally {
      setVerifying(false)
    }
  }

  return { value, setValue, verifying, error, submit }
}
