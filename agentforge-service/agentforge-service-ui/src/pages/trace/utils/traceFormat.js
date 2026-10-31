function trimFixed(value, digits) {
  const text = value.toFixed(digits)
  if (!text.includes('.')) return text
  return text.replace(/0+$/, '').replace(/\.$/, '')
}

export function formatCompactDuration(value) {
  const ms = Number(value || 0)
  if (!Number.isFinite(ms) || ms <= 0) return '0ms'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60000) return `${trimFixed(ms / 1000, ms < 10000 ? 2 : 1)}s`
  return `${trimFixed(ms / 60000, ms < 600000 ? 2 : 1)}min`
}

export function formatCompactNumber(value) {
  const number = Number(value || 0)
  if (!Number.isFinite(number) || number <= 0) return '0'
  if (number >= 1000000) return `${trimFixed(number / 1000000, 2)}m`
  if (number >= 1000) return `${trimFixed(number / 1000, 1)}k`
  return String(Math.round(number))
}

export function formatDurationTitle(value) {
  const ms = Number(value || 0)
  if (!Number.isFinite(ms) || ms <= 0) return '0 ms'
  return `${ms.toLocaleString('zh-CN')} ms`
}

export function formatTokenTitle(value) {
  const number = Number(value || 0)
  if (!Number.isFinite(number) || number <= 0) return '0 tokens'
  return `${number.toLocaleString('zh-CN')} tokens`
}

export function formatTokenDetail(value) {
  const number = Number(value || 0)
  if (!Number.isFinite(number) || number <= 0) return '0'
  const compact = formatCompactNumber(number)
  return compact === String(number) ? compact : `${compact}（${number.toLocaleString('zh-CN')}）`
}
