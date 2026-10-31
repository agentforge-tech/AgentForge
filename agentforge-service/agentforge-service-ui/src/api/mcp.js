import { request } from '../utils/request'

const BASE = '/api/mcp-plugin'

// ---------------- 插件 ----------------

export function fetchMcpPlugins(keyword, onlyEnabled) {
  const params = new URLSearchParams()
  if (keyword) params.append('keyword', keyword)
  if (onlyEnabled) params.append('onlyEnabled', 'true')
  const query = params.toString()
  return request(BASE + '/list' + (query ? '?' + query : ''))
}

export function getMcpPlugin(id) {
  return request(BASE + '/' + id)
}

export function upsertMcpPlugin(payload) {
  return request(BASE + '/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateMcpPluginStatus(id, status) {
  return request(BASE + '/' + id + '/status', { method: 'POST', body: JSON.stringify({ status }) })
}

export function deleteMcpPlugin(id) {
  return request(BASE + '/' + id, { method: 'DELETE' })
}

export function importMcpServers(payload) {
  return request(BASE + '/import', { method: 'POST', body: JSON.stringify(payload) })
}

export function exportMcpServers(id) {
  return request(BASE + '/' + id + '/export')
}

// ---------------- Server ----------------

export function pageMcpServers(query) {
  return request(BASE + '/server/page', { method: 'POST', body: JSON.stringify(query || {}) })
}

export function getMcpServer(id) {
  return request(BASE + '/server/' + id)
}

export function upsertMcpServer(payload) {
  return request(BASE + '/server/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateMcpServerStatus(id, status) {
  return request(BASE + '/server/' + id + '/status', { method: 'POST', body: JSON.stringify({ status }) })
}

export function deleteMcpServer(id) {
  return request(BASE + '/server/' + id, { method: 'DELETE' })
}

export function testMcpServerTools(id) {
  return request(BASE + '/server/' + id + '/tools')
}
