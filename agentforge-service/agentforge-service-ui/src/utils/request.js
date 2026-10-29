export async function request(url, options = {}) {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  if (!response.ok) {
    const raw = await response.text().catch(() => '')
    let message = `请求失败（HTTP ${response.status}）`
    if (raw) {
      try {
        const data = JSON.parse(raw)
        message = (data && (data.message || data.msg)) || raw
      } catch (_error) {
        message = raw
      }
    }
    throw new Error(message)
  }
  if (response.status === 204) return null
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

export function buildQuery(query = {}) {
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') params.append(key, value)
  })
  const value = params.toString()
  return value ? `?${value}` : ''
}

export function jsonBody(payload) {
  return { method: 'POST', body: JSON.stringify(payload || {}) }
}
