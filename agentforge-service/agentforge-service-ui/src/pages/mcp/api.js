const MCP_BASE = '/api/mcp-plugin'

async function request(url, options = {}) {
  const res = await fetch(MCP_BASE + url, {
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

// ---------------- 插件 ----------------

export function fetchMcpPlugins(keyword, onlyEnabled) {
  const params = new URLSearchParams()
  if (keyword) params.append('keyword', keyword)
  if (onlyEnabled) params.append('onlyEnabled', 'true')
  const qs = params.toString()
  return request('/list' + (qs ? '?' + qs : ''))
}

export function getMcpPlugin(id) {
  return request('/' + id)
}

export function upsertMcpPlugin(payload) {
  return request('/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateMcpPluginStatus(id, status) {
  return request('/' + id + '/status', { method: 'POST', body: JSON.stringify({ status }) })
}

export function deleteMcpPlugin(id) {
  return request('/' + id, { method: 'DELETE' })
}

export function importMcpServers(payload) {
  return request('/import', { method: 'POST', body: JSON.stringify(payload) })
}

export function exportMcpServers(id) {
  return request('/' + id + '/export')
}

// ---------------- Server ----------------

export function pageMcpServers(query) {
  return request('/server/page', { method: 'POST', body: JSON.stringify(query || {}) })
}

export function getMcpServer(id) {
  return request('/server/' + id)
}

export function upsertMcpServer(payload) {
  return request('/server/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateMcpServerStatus(id, status) {
  return request('/server/' + id + '/status', { method: 'POST', body: JSON.stringify({ status }) })
}

export function deleteMcpServer(id) {
  return request('/server/' + id, { method: 'DELETE' })
}

export function testMcpServerTools(id) {
  return request('/server/' + id + '/tools')
}
