import { buildQuery, request } from '../utils/request'

const AGENT_BASE = '/api/agent-config'
const MODEL_BASE = '/api/model-config'

export function fetchAgents(query) {
  return request(AGENT_BASE + '/page' + buildQuery(query))
}

export function getAgent(id) {
  return request(AGENT_BASE + '/' + id)
}

export function upsertAgent(payload) {
  return request(AGENT_BASE + '/upsert', { method: 'POST', body: JSON.stringify(payload) })
}

export function fetchAgentVersions(id) {
  return request(AGENT_BASE + '/' + id + '/versions')
}

export function getAgentVersion(id, versionNo) {
  return request(AGENT_BASE + '/' + id + '/versions/' + versionNo)
}

export function updateAgentVersionRemark(id, versionNo, versionRemark) {
  return request(AGENT_BASE + '/' + id + '/versions/' + versionNo + '/remark', { method: 'POST', body: JSON.stringify({ versionRemark }) })
}

export function switchAgentVersion(id, versionNo) {
  return request(AGENT_BASE + '/' + id + '/versions/' + versionNo + '/switch', { method: 'POST' })
}

export function updateAgentPublish(id, published) {
  return request(AGENT_BASE + '/' + id + (published ? '/publish' : '/unpublish'), { method: 'POST' })
}

export function deleteAgent(id) {
  return request(AGENT_BASE + '/' + id, { method: 'DELETE' })
}

export function chatAgent(payload) {
  return request('/api/agent/chat', { method: 'POST', body: JSON.stringify(payload) })
}

export function fetchChatModels() {
  return request(MODEL_BASE + '/page?page=1&pageSize=100&modelType=0')
}
