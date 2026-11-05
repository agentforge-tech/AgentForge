/**
 * 用户追加消息状态。
 *
 * 数字值与后端 UserMessageAppendStatus 保持一致，用于解析 user_message 流式事件。
 */
export const USER_MESSAGE_APPEND_STATUS = Object.freeze({
  /** QUEUED：消息已进入追加队列，正在等待下一个 Agent Loop 模型调用边界。 */
  QUEUED: 1,

  /** EFFECTIVE：消息已注入 Agent Loop，并开始对后续模型调用生效。 */
  EFFECTIVE: 2,

  /** DEFERRED：当前 Agent Loop 已结束，消息未来得及注入，需延后到后续对话处理。 */
  DEFERRED: 3,

  /** CANCELED：消息在生效前已被用户取消，不再注入 Agent Loop。 */
  CANCELED: 4
})
