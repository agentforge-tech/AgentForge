/**
 * 历史消息事件重放：把后端 events 字段（SSE 事件 JSON 数组字符串）还原成渲染所需的 message 结构。
 * 与实时 handleEvent 的处理逻辑保持同一语义，保证实时展示与历史展示一致。
 */
export function parseEvents(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch (_error) {
    return []
  }
}

function applyThink(message, event) {
  const last = message.parts[message.parts.length - 1]
  const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
  const text = event.content || ''
  if (!event.isFinish) {
    if (open) open.content += text
    else message.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: 0, duration: null })
    return
  }
  if (open) {
    if (text) open.content = text
    open.status = 'end'
    open.duration = null
  } else if (text) {
    message.parts.push({ type: 'think', content: text, status: 'end', startedAt: 0, duration: null })
  }
}

function closeOpenThink(message) {
  const last = message.parts[message.parts.length - 1]
  if (last && last.type === 'think' && last.status === 'thinking') {
    last.status = 'end'
    last.duration = null
  }
}

function applyTool(message, event) {
  const content = event.content || {}
  let part = message.parts.find(
    (p) => p.type === 'tool_card' && p.toolCallId && p.toolCallId === content.toolCallId
  )
  if (!part) {
    part = {
      type: 'tool_card',
      toolCallId: content.toolCallId || '',
      toolName: content.toolName || 'unknown',
      methodName: '',
      description: '',
      inputParams: null,
      outputResult: null,
      duration: null,
      isFinish: false,
      startedAt: 0,
      finishedAt: null
    }
    message.parts.push(part)
  }
  part.toolCallId = content.toolCallId || part.toolCallId
  part.toolName = content.toolName || part.toolName
  if (content.description) part.description = content.description
  if (content.inputParams != null) part.inputParams = content.inputParams
  if (content.outputResult != null) part.outputResult = content.outputResult
  const duration = Number(content.duration)
  if (Number.isFinite(duration) && duration > 0) part.duration = duration
  part.isFinish = !!event.isFinish
}

/** 把一条 AI 消息记录（含 events）还原为渲染用 message 对象 */
export function replayMessage(record) {
  const message = {
    role: 'assistant',
    status: 'success',
    parts: [],
    content: '',
    error: '',
    startedAt: record.startedAt || 0,
    finishedAt: record.finishedAt || null,
    elapsedMs: record.elapsedMs || null
  }
  parseEvents(record.events).forEach((event) => {
    switch (event.event) {
      case 'think':
        applyThink(message, event)
        break
      case 'resp':
        message.content += event.content || ''
        break
      case 'tool_call':
        closeOpenThink(message)
        applyTool(message, event)
        break
      case 'error':
        message.status = 'error'
        message.error =
          typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
        break
      default:
        break
    }
  })
  if (record.status === 3) {
    message.status = 'error'
    if (!message.error) message.error = record.contentText || '生成失败'
  } else if (record.status === 4) {
    message.status = 'error'
    if (!message.error) message.error = '本轮已取消'
  } else if (record.status === 1 || record.status === 2) {
    message.status = 'running'
  }
  if (!message.content && record.contentText) message.content = record.contentText
  message.parts.forEach((part) => {
    if (part.type === 'think' && part.status === 'thinking') part.status = 'end'
    if (part.type === 'tool_card' && !part.isFinish) part.isFinish = true
  })
  return message
}

/** 会话消息明细 → 页面 messages 数组 */
export function replaySessionMessages(records) {
  const messages = []
  ;(records || []).forEach((record) => {
    if (record.roleType === 1) {
      messages.push({ role: 'user', text: record.contentText || '' })
      return
    }
    messages.push(replayMessage(record))
  })
  return messages
}
