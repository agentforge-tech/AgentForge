/**
 * 将毫秒耗时格式化为适合会话状态栏的紧凑文案。
 * < 60s: xx秒；< 1h: xx分xx秒；>= 1h: xx小时xx分。
 */
export function formatElapsedDuration(milliseconds) {
  const value = Number(milliseconds)
  if (!Number.isFinite(value) || value <= 0) return ''
  const totalSeconds = Math.max(1, Math.round(value / 1000))
  if (totalSeconds < 60) return `${totalSeconds}秒`
  if (totalSeconds < 3600) {
    return `${Math.floor(totalSeconds / 60)}分${totalSeconds % 60}秒`
  }
  return `${Math.floor(totalSeconds / 3600)}小时${Math.floor((totalSeconds % 3600) / 60)}分`
}
