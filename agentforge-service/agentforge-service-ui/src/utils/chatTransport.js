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

async function consumeDetached(messageId, afterSeq, { signal, onEvent }) {
  let cursor = Number(afterSeq || 0)
  let terminal = false
  while (!terminal) {
    const url = `/api/chat/tasks/stream?messageId=${encodeURIComponent(messageId)}&afterSeq=${encodeURIComponent(cursor)}`
    try {
      await getSse(url, {
        signal,
        onEvent: (event) => {
          const seq = Number(event.eventSeq || 0)
          if (seq > cursor) cursor = seq
          terminal = event.event === 'resp_end' || event.event === 'error'
          onEvent(event)
        }
      })
    } catch (error) {
      if (error.name === 'AbortError' || signal?.aborted) throw error
      await delay(500, signal)
      continue
    }
    if (!terminal) await delay(300, signal)
  }
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
