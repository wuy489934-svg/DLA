import { create } from 'zustand'
import type { TranslationMessage } from '../../i18n/index.js'
import { createId } from '../../utils/id.js'
import {
  cancelUpload,
  isAcceptedExt,
  isImage,
  resolveMimeType,
  uploadAttachment,
  validateFiles,
  type UploadTask,
} from '../attachments.js'
import { saveAgentFile } from '../agentFiles.js'
import type { FileAttachment } from './types.js'

export type DraftAttachment = {
  /** 本地标识，也是 IndexedDB 原件与上传任务的 key */
  id: string
  file: File
  isImage: boolean
  /** Agent 本地文件保存完成后的路径；有值时不需要上传 */
  localPath?: string
  localSaving?: boolean
  localError?: boolean
}

type Draft = { text: string; attachments: DraftAttachment[] }

export const EMPTY_DRAFT: Draft = { text: '', attachments: [] }

/** 草稿只保存在本次页面的内存中，独立于会话详情缓存；null 是新对话的草稿。 */
export const useDrafts = create<{ drafts: Map<string | null, Draft> }>(() => ({
  drafts: new Map(),
}))

export const getDraft = (sessionId: string | null): Draft =>
  useDrafts.getState().drafts.get(sessionId) ?? EMPTY_DRAFT

const updateDraft = (sessionId: string | null, update: (draft: Draft) => Draft) =>
  useDrafts.setState((state) => {
    const draft = update(state.drafts.get(sessionId) ?? EMPTY_DRAFT)
    const drafts = new Map(state.drafts)
    if (draft.text || draft.attachments.length > 0) {
      drafts.set(sessionId, draft)
    } else {
      drafts.delete(sessionId)
    }
    return { drafts }
  })

export const setDraftText = (sessionId: string | null, text: string) =>
  updateDraft(sessionId, (draft) => ({ ...draft, text }))

const upload = (attachment: DraftAttachment, apiKey: string) => {
  // 上传任务负责记录错误，输入框的附件卡片展示失败及重试入口。
  void uploadAttachment(
    apiKey,
    attachment.id,
    attachment.file,
    attachment.file.name,
    attachment.isImage
  ).catch(() => {})
}

/** Agent 专属文件：保存到本地，拿到路径后交给手机工具解析 */
const saveLocal = (sessionId: string | null, attachment: DraftAttachment) => {
  updateDraft(sessionId, (draft) => ({
    ...draft,
    attachments: draft.attachments.map((item) =>
      item.id === attachment.id
        ? { ...item, localSaving: true, localError: false, localPath: undefined }
        : item
    ),
  }))
  void saveAgentFile(attachment.file)
    .then((path) => {
      updateDraft(sessionId, (draft) => ({
        ...draft,
        attachments: draft.attachments.map((item) =>
          item.id === attachment.id
            ? { ...item, localPath: path, localSaving: false, localError: false }
            : item
        ),
      }))
    })
    .catch(() => {
      updateDraft(sessionId, (draft) => ({
        ...draft,
        attachments: draft.attachments.map((item) =>
          item.id === attachment.id
            ? { ...item, localSaving: false, localError: true }
            : item
        ),
      }))
    })
}

/** 整批校验通过后才加入草稿并开始上传；agent 模式允许任意扩展名，非白名单文件存本地。 */
export const addDraftFiles = (
  sessionId: string | null,
  files: File[],
  apiKey: string,
  options?: { agent?: boolean }
): TranslationMessage | null => {
  if (files.length === 0) {
    return null
  }
  const agent = options?.agent === true
  const error = validateFiles(files, getDraft(sessionId).attachments.length, agent)
  if (error) {
    return error
  }
  const added = files.map(
    (file): DraftAttachment => ({
      id: createId(),
      file,
      isImage: isImage(resolveMimeType(file)),
      localSaving: agent && !isAcceptedExt(file.name),
    })
  )
  updateDraft(sessionId, (draft) => ({ ...draft, attachments: [...draft.attachments, ...added] }))
  added.forEach((attachment) => {
    if (attachment.localSaving) {
      saveLocal(sessionId, attachment)
    } else {
      upload(attachment, apiKey)
    }
  })
  return null
}

export const retryDraftAttachment = (
  sessionId: string | null,
  attachmentId: string,
  apiKey: string
) => {
  const attachment = getDraft(sessionId).attachments.find((item) => item.id === attachmentId)
  if (attachment) {
    if (attachment.localSaving || attachment.localError || attachment.localPath) {
      saveLocal(sessionId, attachment)
    } else {
      upload(attachment, apiKey)
    }
  }
}

/** 发送按钮和实际提交共用规则：有内容，且全部附件已上传成功。 */
export const prepareDraftMessage = (draft: Draft, tasks: Record<string, UploadTask>) => {
  const text = draft.text.trim()
  if (!text && draft.attachments.length === 0) {
    return null
  }
  const files: (FileAttachment & { serverId: string; blob: Blob })[] = []
  for (const attachment of draft.attachments) {
    if (attachment.localPath) {
      files.push({
        id: attachment.id,
        serverId: '',
        localPath: attachment.localPath,
        name: attachment.file.name,
        size: attachment.file.size,
        isImage: attachment.isImage,
        mimeType: resolveMimeType(attachment.file),
        blob: attachment.file,
      })
      continue
    }
    const task = tasks[attachment.id]
    if (task?.status !== 'done' || !task.serverId) {
      return null
    }
    files.push({
      id: attachment.id,
      serverId: task.serverId,
      name: attachment.file.name,
      size: attachment.file.size,
      isImage: attachment.isImage,
      mimeType: resolveMimeType(attachment.file),
      blob: attachment.file,
    })
  }
  return { text, files }
}

export const removeDraftAttachment = (sessionId: string | null, attachmentId: string) => {
  const attachment = getDraft(sessionId).attachments.find((item) => item.id === attachmentId)
  if (!attachment) {
    return
  }
  cancelUpload(attachment.id)
  updateDraft(sessionId, (draft) => ({
    ...draft,
    attachments: draft.attachments.filter((item) => item.id !== attachmentId),
  }))
}

/** 发送成功后原件与 serverId 已交给会话，保留上传完成态。 */
export const clearSentDraft = (sessionId: string | null) => {
  updateDraft(sessionId, () => EMPTY_DRAFT)
}

/** 删除会话或替换历史成功后，释放尚未发送的草稿及上传任务。 */
export const clearDraft = (sessionId: string | null) => {
  getDraft(sessionId).attachments.forEach((attachment) => cancelUpload(attachment.id))
  updateDraft(sessionId, () => EMPTY_DRAFT)
}

export const clearAllDrafts = () => {
  for (const draft of useDrafts.getState().drafts.values()) {
    draft.attachments.forEach((attachment) => cancelUpload(attachment.id))
  }
  useDrafts.setState({ drafts: new Map() })
}
