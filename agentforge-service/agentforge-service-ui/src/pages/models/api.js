// ---------------------------------------------------------------------------
// 我的模型 —— 后端接口封装（cloud.changlu.agentforge.service.modules.llm）
// 统一走 /api 前缀，由 vite 代理到本地服务（默认 http://localhost:8080）。
// ---------------------------------------------------------------------------

const BASE = '/api/model-config'

async function request(url, options = {}) {
  const res = await fetch(BASE + url, {
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
      } catch (err) {
        message = raw
      }
    }
    throw new Error(message)
  }
  if (res.status === 204) return null
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

export function fetchOptions() {
  return request('/options')
}

export function fetchModels(query = {}) {
  const params = new URLSearchParams()
  Object.keys(query).forEach((key) => {
    const value = query[key]
    if (value !== undefined && value !== null && value !== '') {
      params.append(key, value)
    }
  })
  const qs = params.toString()
  return request('/page' + (qs ? '?' + qs : ''))
}

export function getModel(id) {
  return request('/' + id)
}

export function upsertModel(payload) {
  return request('/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function deleteModel(id) {
  return request('/' + id, { method: 'DELETE' })
}

// 测试连通性 / Function Call：统一携带 modelId + provider + modelType 枚举值
export function testConnectivity(payload) {
  return request('/testConn', { method: 'POST', body: JSON.stringify(payload) })
}

export function testFunctionCall(payload) {
  return request('/testFunctionCall', { method: 'POST', body: JSON.stringify(payload) })
}
