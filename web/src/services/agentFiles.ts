/** Agent 专属本地文件：分块通过 JS Bridge 写入 app 外部目录，APK/ZIP 等不需要 Files API */
export const canSaveAgentFile = () =>
  typeof window.AndroidHost?.attachmentBegin === 'function' &&
  typeof window.AndroidHost?.attachmentChunk === 'function' &&
  typeof window.AndroidHost?.attachmentEnd === 'function'

const toBase64 = (bytes: Uint8Array): string => {
  let binary = ''
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 8192))
  }
  return btoa(binary)
}

export const saveAgentFile = async (file: File): Promise<string> => {
  const host = window.AndroidHost
  if (!host?.attachmentBegin || !host.attachmentChunk || !host.attachmentEnd) {
    throw new Error('当前环境不支持保存本地附件')
  }
  const started = JSON.parse(host.attachmentBegin(file.name, file.type || 'application/octet-stream'))
  if (!started.ok) {
    throw new Error(started.error ?? '附件保存失败')
  }
  const id = String(started.id)
  const chunkSize = 192 * 1024
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    const buffer = await file.slice(offset, offset + chunkSize).arrayBuffer()
    if (!host.attachmentChunk(id, toBase64(new Uint8Array(buffer)))) {
      throw new Error('附件写入失败')
    }
  }
  const ended = JSON.parse(host.attachmentEnd(id))
  if (!ended.ok) {
    throw new Error(ended.error ?? '附件保存失败')
  }
  return String(ended.path)
}
