const SESSION_BASE = '/api/agent/sessions'
const MESSAGE_BASE = '/api/agent/session-messages'

async function request(base, url, body) {
  const res = await fetch(base + url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {})
  })
  if (!res.ok) {
    const raw = await res.text().catch(() => '')
    let message = `请求失败（HTTP ${res.status}）`
    if (raw) {
      try {
        const data = JSON.parse(raw)
        message = (data && data.message) || raw
      } catch (_err) {
        message = raw
      }
    }
    throw new Error(message)
  }
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

/** 普通历史会话（未置顶），按最近修改时间倒序；debug 为空表示不区分调试/发布态 */
export function fetchSessionList(aid, limit, debug) {
  return request(SESSION_BASE, '/sessionList', { aid, limit, debug })
}

/** 置顶会话，按置顶序号倒序 */
export function fetchTopSessionList(aid) {
  return request(SESSION_BASE, '/topList', { aid })
}

export function setSessionTop(sessionId, isTop) {
  return request(SESSION_BASE, '/setTop', { sessionId, isTop })
}

export function changeSessionTitle(sessionId, title) {
  return request(SESSION_BASE, '/changeTitle', { sessionId, title })
}

export function deleteSession(sessionId) {
  return request(SESSION_BASE, '/delete', { sessionId })
}

/** 会话消息明细，events 为 SSE 事件 JSON 数组字符串 */
export function fetchSessionMessages(sessionId) {
  return request(MESSAGE_BASE, '/detail', { sessionId })
}

/**
 * 会话标题统一规则：去首尾空白、连续空白合并为单个空格，按字符取前 20 个。
 * 与后端 SessionTitleUtil 保持一致。
 */
export const TITLE_MAX_LENGTH = 20

export function resolveSessionTitle(text) {
  const normalized = String(text || '').trim().replace(/\s+/g, ' ')
  if (!normalized) return '新会话'
  const chars = Array.from(normalized)
  return chars.length <= TITLE_MAX_LENGTH ? normalized : chars.slice(0, TITLE_MAX_LENGTH).join('')
}
