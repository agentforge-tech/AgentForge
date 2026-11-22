import { createAppendState, reduceAppendEvent } from './appendEventReducer'
import { reduceCompactEvent } from './compactEventReducer'
import { buildTodoCardPart } from './todoEventReducer'
import { reduceInterruptAnswers, reduceInterruptEvent } from './interruptEventReducer'
import {
  appendResponseSegment,
  closeOpenResponseSegment,
  finishResponseSegments,
  promoteLastResponseSegment,
  shouldAppendResponseSegment
} from './responseSegmentReducer'

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
  if (!String(text).trim() && !open) return
  if (!event.isFinish) {
    if (open) open.content += text
    else message.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: 0, duration: null })
    return
  }
  if (open) {
    if (String(text).trim()) open.content = text
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

function applyTool(message, event, detailed = false) {
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
  if (!detailed && part.isFinish) replaceWithTodoCard(message, part)
}

function replaceWithTodoCard(message, part) {
  const todoPart = buildTodoCardPart(part)
  if (!todoPart) return
  const index = message.parts.indexOf(part)
  if (index >= 0) message.parts.splice(index, 1, todoPart)
}

/** 把一条 AI 消息记录（含 events）还原为渲染用 message 对象 */
export function replayMessage(record, appendState = createAppendState(), detailed = false) {
  const events = parseEvents(record.events)
  let sawRespEnd = false
  const message = {
    role: 'assistant',
    messageId: record.id,
    status: 'success',
    parts: [],
    content: '',
    error: '',
    startedAt: record.startedAt || 0,
    finishedAt: record.finishedAt || null,
    elapsedMs: record.elapsedMs || null,
    lastEventSeq: 0
  }
  events.forEach((event) => {
    message.lastEventSeq = Math.max(message.lastEventSeq, Number(event.eventSeq || 0))
    switch (event.event) {
      case 'think':
        closeOpenResponseSegment(message, () => 0)
        applyThink(message, event)
        break
      case 'resp':
        if (shouldAppendResponseSegment(message, event)) {
          closeOpenThink(message)
          appendResponseSegment(message, event, () => 0)
        }
        break
      case 'tool_call':
        closeOpenResponseSegment(message, () => 0)
        closeOpenThink(message)
        applyTool(message, event, detailed)
        reduceInterruptAnswers(message, event)
        break
      case 'user_message':
        closeOpenResponseSegment(message, () => 0)
        reduceAppendEvent(appendState, message, event)
        break
      case 'compact':
        closeOpenResponseSegment(message, () => 0)
        reduceCompactEvent(message, event, detailed)
        break
      case 'retry':
        closeOpenResponseSegment(message, () => 0)
        break
      case 'interrupt':
        closeOpenResponseSegment(message, () => 0)
        closeOpenThink(message)
        reduceInterruptEvent(message, event)
        break
      case 'resp_end':
        sawRespEnd = true
        promoteLastResponseSegment(message, () => 0)
        break
      case 'error':
        finishResponseSegments(message, () => 0)
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
  } else if (record.status === 4 || record.status === 6) {
    // 4=CANCELLED，6=TIMEOUT_CANCELLED：都属于取消，不是失败。
    message.status = 'cancelled'
  } else if (record.status === 8) {
    // 8=WAITING_HITL：等待用户确认，保留运行锁但不继续订阅。
    message.status = 'waiting'
  } else if (record.status === 1 || record.status === 2 || record.status === 7) {
    // 1=CREATED，2=RUNNING，7=CANCELLING。
    message.status = 'running'
  }
  // 旧数据可能没有 resp_end；已成功的消息仍将最后响应片段视为最终回答。
  if (message.status === 'success' && !sawRespEnd && !message.content) {
    promoteLastResponseSegment(message, () => 0)
  }
  // 只有完全没有事件明细时才使用历史正文兜底，避免把已拼接的中间响应放回正文。
  if (!events.length && !message.content && record.contentText) message.content = record.contentText
  // 运行中的消息仍可能有未结束的 think/tool，只有终态消息才强制收尾。
  if (message.status !== 'running') {
    message.parts.forEach((part) => {
      if (part.type === 'think' && part.status === 'thinking') part.status = 'end'
      if (part.type === 'tool_card' && !part.isFinish) part.isFinish = true
      if (part.type === 'response_segment' && part.status === 'streaming') part.status = 'complete'
    })
  }
  return message
}

/**
 * 会话消息明细 → 页面渲染状态。
 *
 * <p>返回 messages 以及页面级追加状态（pendingAppends/deferredAppends），
 * 两者都由同一条事件序列经 {@link reduceAppendEvent} 推导，实时与历史语义一致。
 * 历史重放时 pending 正常情况下应为空：已完成的 Run 里 QUEUED 一定会有终态事件。
 */
/** 从用户消息的 req 快照中解析文件 */
export function parseUserAttachments(record) {
  if (!record || !record.req) return []
  let req
  try {
    req = typeof record.req === 'string' ? JSON.parse(record.req) : record.req
  } catch (_error) {
    return []
  }
  if (!req || !Array.isArray(req.files)) return []
  return req.files
    .filter((item) => item && item.visitUrl)
    .map((item) => ({
      fileId: item.fileId,
      name: item.originFileName,
      fileType: item.fileType,
      fileSize: item.fileSize,
      visitUrl: item.visitUrl
    }))
}

export function replaySession(records, detailed = false) {
  const state = createAppendState()
  const messages = []
  ;(records || []).forEach((record) => {
    if (record.roleType === 1) {
      const userMessage = { role: 'user', text: record.contentText || '' }
      const attachments = parseUserAttachments(record)
      if (attachments.length) userMessage.attachments = attachments
      messages.push(userMessage)
      return
    }
    messages.push(replayMessage(record, state, detailed))
  })
  return {
    messages,
    pendingAppends: state.pendingAppends,
    deferredAppends: state.deferredAppends
  }
}

/** 会话消息明细 → 页面 messages 数组（仅需要消息时使用） */
export function replaySessionMessages(records) {
  return replaySession(records).messages
}
