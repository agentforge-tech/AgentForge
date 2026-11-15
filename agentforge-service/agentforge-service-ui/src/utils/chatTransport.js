import { getSse } from './sse'

/** 固定二段式（submit + stream）：legacy 单请求 SSE 已停用。 */
export function chatTransportMode() {
  return 'detached'
}

export function setChatTransportMode() {
  // legacy 已停用，保留空实现仅用于兼容旧引用。
}

export function isDetachedChatTransport() {
  return true
}

export async function runChatStream(body, { signal, onSubmitted, onEvent }) {
  const response = await fetch('/api/chat/tasks/submit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal
  })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`)
  if (onSubmitted) onSubmitted(result)
  await consumeDetached(result.messageId, 0, { signal, onEvent })
  return result
}

export async function resumeChatStream(messageId, afterSeq, options) {
  return consumeDetached(messageId, afterSeq, options)
}

/** 一次性提交 HITL 批次的审批结果或 AskUser 回答。 */
export async function resumeInterrupt(batchId, responses) {
  const response = await fetch('/api/chat/tasks/interrupts/resume', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ batchId, responses })
  })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`)
  return result
}

async function consumeDetached(messageId, afterSeq, { signal, onEvent }) {
  let cursor = Number(afterSeq || 0)
  let terminal = false
  let lastEvent = null
  while (true) {
    const url = `/api/chat/tasks/stream?messageId=${encodeURIComponent(messageId)}&afterSeq=${encodeURIComponent(cursor)}`
    try {
      await getSse(url, {
        signal,
        onEvent: (event) => {
          const seq = Number(event.eventSeq || 0)
          if (seq > cursor) cursor = seq
          lastEvent = event
          terminal = event.event === 'resp_end' || event.event === 'error'
          onEvent(event)
        }
      })
    } catch (error) {
      if (error.name === 'AbortError' || signal?.aborted) throw error
      if (isInterruptPause(lastEvent)) return { lastSeq: cursor, paused: true }
      await delay(500, signal)
      continue
    }
    if (terminal) break
    // 只有“服务端在中断事件之后收口”才算暂停；历史回放中的旧中断不会提前截断。
    if (isInterruptPause(lastEvent)) return { lastSeq: cursor, paused: true }
    await delay(300, signal)
  }
  return { lastSeq: cursor, paused: false }
}

/** 中断请求是 stream 的暂停点：服务端随后会关闭本次 SSE，恢复由新请求续流。 */
export function isInterruptPause(event) {
  return event && event.event === 'interrupt' && event.type === 'interrupt_requested'
}

function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    if (!signal) return
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      const error = new Error('Aborted')
      error.name = 'AbortError'
      reject(error)
    }, { once: true })
  })
}
