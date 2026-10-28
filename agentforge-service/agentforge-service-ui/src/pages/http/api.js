const BASE = '/api'

async function request(url, options = {}) {
  const res = await fetch(BASE + url, { headers: { 'Content-Type': 'application/json' }, ...options })
  if (!res.ok) {
    const raw = await res.text().catch(() => '')
    try { throw new Error((JSON.parse(raw) || {}).message || raw || `请求失败（HTTP ${res.status}）`) } catch (e) { if (e instanceof SyntaxError) throw new Error(raw || `请求失败（HTTP ${res.status}）`); throw e }
  }
  if (res.status === 204) return null
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

const body = (payload) => ({ method: 'POST', body: JSON.stringify(payload || {}) })
export const pageHttpPlugins = (query) => request('/http-plugin/page?' + new URLSearchParams(Object.entries(query || {}).filter(([, value]) => value !== '' && value !== null && value !== undefined)))
export const getHttpPlugin = (id) => request('/http-plugin/' + id)
export const saveHttpPlugin = (payload) => request('/http-plugin/upsert', body(payload))
export const setHttpPluginStatus = (id, status) => request(`/http-plugin/${id}/status`, body({ status }))
export const deleteHttpPlugin = (id) => request('/http-plugin/' + id, { method: 'DELETE' })
export const listHttpMethods = (pluginId) => request(`/http-plugin/${pluginId}/methods`)
export const getHttpMethod = (id) => request('/http-plugin-method/' + id)
export const saveHttpMethod = (payload) => request('/http-plugin-method/upsert', body(payload))
export const setHttpMethodStatus = (id, status) => request(`/http-plugin-method/${id}/status`, body({ status }))
export const deleteHttpMethod = (id) => request('/http-plugin-method/' + id, { method: 'DELETE' })
export const testHttpMethod = (id, args) => request(`/http-plugin-method/${id}/test-run`, body({ arguments: args }))
export const previewCurl = (payload) => request('/http-plugin/curl/preview', body(payload))
export const importCurl = (payload) => request('/http-plugin/curl/import', body(payload))
export const importPluginJson = (payload) => request('/http-plugin/json/import', body(payload))
export const exportPluginJson = (id) => request(`/http-plugin/${id}/export`)
export const fetchHttpPluginOptions = () => request('/http-plugin/options')
