import { buildQuery, jsonBody, request } from '../utils/request'

const BASE = '/api'

export const pageHttpPlugins = (query) => request(BASE + '/http-plugin/page' + buildQuery(query))
export const getHttpPlugin = (id) => request(BASE + '/http-plugin/' + id)
export const saveHttpPlugin = (payload) => request(BASE + '/http-plugin/upsert', jsonBody(payload))
export const setHttpPluginStatus = (id, status) => request(`${BASE}/http-plugin/${id}/status`, jsonBody({ status }))
export const deleteHttpPlugin = (id) => request(BASE + '/http-plugin/' + id, { method: 'DELETE' })
export const listHttpMethods = (pluginId) => request(`${BASE}/http-plugin/${pluginId}/methods`)
export const getHttpMethod = (id) => request(BASE + '/http-plugin-method/' + id)
export const saveHttpMethod = (payload) => request(BASE + '/http-plugin-method/upsert', jsonBody(payload))
export const setHttpMethodStatus = (id, status) => request(`${BASE}/http-plugin-method/${id}/status`, jsonBody({ status }))
export const deleteHttpMethod = (id) => request(BASE + '/http-plugin-method/' + id, { method: 'DELETE' })
export const testHttpMethod = (id, args) => request(`${BASE}/http-plugin-method/${id}/test-run`, jsonBody({ arguments: args }))
export const previewCurl = (payload) => request(BASE + '/http-plugin/curl/preview', jsonBody(payload))
export const importCurl = (payload) => request(BASE + '/http-plugin/curl/import', jsonBody(payload))
export const importPluginJson = (payload) => request(BASE + '/http-plugin/json/import', jsonBody(payload))
export const exportPluginJson = (id) => request(`${BASE}/http-plugin/${id}/export`)
export const fetchHttpPluginOptions = () => request(BASE + '/http-plugin/options')
