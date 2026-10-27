const AGENT_BASE = '/api/agent-config'
const MODEL_BASE = '/api/model-config'

async function request(base, url, options = {}) {
  const res = await fetch(base + url, {
    headers: { 'Content-Type': 'application/json' },
    ...options
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
  if (res.status === 204) return null
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

function queryString(query) {
  const params = new URLSearchParams()
  Object.keys(query).forEach((key) => {
    const value = query[key]
    if (value !== undefined && value !== null && value !== '') params.append(key, value)
  })
  const value = params.toString()
  return value ? '?' + value : ''
}

export function fetchAgents(query) {
  return request(AGENT_BASE, '/page' + queryString(query))
}

export function getAgent(id) {
  return request(AGENT_BASE, '/' + id)
}

export function upsertAgent(payload) {
  return request(AGENT_BASE, '/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function fetchAgentVersions(id) {
  return request(AGENT_BASE, '/' + id + '/versions')
}

export function getAgentVersion(id, versionNo) {
  return request(AGENT_BASE, '/' + id + '/versions/' + versionNo)
}

export function updateAgentVersionRemark(id, versionNo, versionRemark) {
  return request(AGENT_BASE, '/' + id + '/versions/' + versionNo + '/remark', { method: 'POST', body: JSON.stringify({ versionRemark }) })
}

export function switchAgentVersion(id, versionNo) {
  return request(AGENT_BASE, '/' + id + '/versions/' + versionNo + '/switch', { method: 'POST' })
}

export function updateAgentPublish(id, published) {
  return request(AGENT_BASE, '/' + id + (published ? '/publish' : '/unpublish'), { method: 'POST' })
}

export function deleteAgent(id) {
  return request(AGENT_BASE, '/' + id, { method: 'DELETE' })
}

export function chatAgent(payload) {
  return request('/api/agent', '/chat', { method: 'POST', body: JSON.stringify(payload) })
}

export function fetchChatModels() {
  return request(MODEL_BASE, '/page?page=1&pageSize=100&modelType=0')
}
