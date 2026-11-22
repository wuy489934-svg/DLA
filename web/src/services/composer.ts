import { create } from 'zustand'

/** 新会话的形态选择；创建会话时写入 Session.mode，之后不可切换。 */
export type ComposerMode = 'llm' | 'agent'

export const useComposer = create<{ mode: ComposerMode }>(() => ({ mode: 'llm' }))

export const setComposerMode = (mode: ComposerMode) => useComposer.setState({ mode })
