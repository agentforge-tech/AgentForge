export function reduceCompactEvent(answer, event, detailed = false) {
  if (!answer || !Array.isArray(answer.parts)) return
  const content = event.content || {}
  const trigger = content.trigger || 'model_call'
  const status = event.type || content.status || 'compacting'
  if (detailed && (status === 'compacted' || status === 'compact_failed')) {
    answer.parts.forEach((candidate) => {
      if (
        candidate.type === 'compact_notice' &&
        candidate.trigger === trigger &&
        candidate.status === 'compacting'
      ) {
        candidate.finished = true
      }
    })
  }
  let part = null
  for (let index = answer.parts.length - 1; index >= 0; index--) {
    const candidate = answer.parts[index]
    if (candidate.type !== 'compact_notice' || candidate.trigger !== trigger) continue
    // 调试模式保留压缩前、压缩后两条独立记录；发布模式合并为一条状态记录。
    if (detailed ? candidate.status === status : candidate.status === 'compacting') {
      part = candidate
      break
    }
  }
  if (!part) {
    part = { type: 'compact_notice', trigger, status: 'compacting' }
    answer.parts.push(part)
  }
  Object.assign(part, content, {
    type: 'compact_notice',
    trigger,
    status
  })
}
