import { request } from '../utils/request'

export function fetchTraceSessions(aid = '', debug = null) {
  const params = []
  if (aid) params.push(`aid=${encodeURIComponent(aid)}`)
  if (debug === true || debug === false) params.push(`debug=${debug}`)
  const query = params.length ? `?${params.join('&')}` : ''
  return request(`/api/agent-trace/sessions${query}`)
}

export function fetchSessionTrace(sessionId) {
  return request(`/api/agent-trace/session/${encodeURIComponent(sessionId)}`)
}
