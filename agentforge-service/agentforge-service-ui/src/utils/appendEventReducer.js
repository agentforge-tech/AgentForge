import { USER_MESSAGE_APPEND_STATUS } from './userMessageAppendStatus'

/**
 * 追加消息事件 reducer。
 *
 * <p>实时 SSE 与历史重放共用同一份状态机，保证「页面展示由事件序列决定」：
 *
 * <ul>
 *   <li>QUEUED：加入 pendingAppends，输入框上方出现等待生效的 pill
 *   <li>EFFECTIVE：移出 pendingAppends，作为过程 part 插入当前 AI 消息
 *   <li>DEFERRED：移出 pendingAppends，进入 deferredAppends，流结束后恢复为草稿
 *   <li>CANCELED：仅移出 pendingAppends
 * </ul>
 *
 * <p>{@code state} 需要提供 {@code pendingAppends}/{@code deferredAppends} 两个数组；
 * {@code answer} 为当前 AI 消息，可为空（历史重放时逐条传入）。
 */
export function reduceAppendEvent(state, answer, event) {
  const content = (event && event.content) || {}
  const appendId = content.appendId
  if (!appendId || !state || !Array.isArray(state.pendingAppends) || !Array.isArray(state.deferredAppends)) {
    return
  }
  if (content.status === USER_MESSAGE_APPEND_STATUS.QUEUED) {
    if (!state.pendingAppends.some((item) => item.appendId === appendId)) {
      state.pendingAppends.push({
        appendId,
        content: content.content || '',
        queuePosition: content.queuePosition,
        queuedAt: content.queuedAt
      })
    }
    return
  }
  state.pendingAppends = state.pendingAppends.filter((item) => item.appendId !== appendId)
  if (content.status === USER_MESSAGE_APPEND_STATUS.EFFECTIVE) {
    if (answer && Array.isArray(answer.parts) &&
        !answer.parts.some((part) => part.type === 'appended_user_message' && part.appendId === appendId)) {
      answer.parts.push({
        type: 'appended_user_message',
        appendId,
        content: content.content || '',
        effectiveStep: content.effectiveStep
      })
    }
  } else if (content.status === USER_MESSAGE_APPEND_STATUS.DEFERRED) {
    if (!state.deferredAppends.some((item) => item.appendId === appendId)) {
      state.deferredAppends.push({
        appendId,
        content: content.content || '',
        queuedAt: content.queuedAt
      })
    }
  }
}

/** 历史重放时按事件顺序创建一个页面级追加状态容器。 */
export function createAppendState() {
  return { pendingAppends: [], deferredAppends: [] }
}
