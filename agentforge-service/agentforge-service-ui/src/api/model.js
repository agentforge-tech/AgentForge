import { buildQuery, request } from '../utils/request'

const BASE = '/api/model-config'

export function fetchOptions() {
  return request(BASE + '/options')
}

export function fetchModels(query = {}) {
  return request(BASE + '/page' + buildQuery(query))
}

export function getModel(id) {
  return request(BASE + '/' + id)
}

export function upsertModel(payload) {
  return request(BASE + '/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function deleteModel(id) {
  return request(BASE + '/' + id, { method: 'DELETE' })
}

// 测试连通性 / Function Call：统一携带 modelId + provider + modelType 枚举值
export function testConnectivity(payload) {
  return request(BASE + '/testConn', { method: 'POST', body: JSON.stringify(payload) })
}

export function testFunctionCall(payload) {
  return request(BASE + '/testFunctionCall', { method: 'POST', body: JSON.stringify(payload) })
}
