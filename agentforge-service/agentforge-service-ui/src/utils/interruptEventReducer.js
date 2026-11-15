/** 将同一 batchId 的中断生命周期事件归并为一张交互卡片。 */
export function reduceInterruptEvent(message, event) {
  const content = event.content || {}
  const batchId = content.batchId
  if (!batchId) return null
  let part = (message.parts || []).find((item) => item.type === 'interrupt_card' && item.batchId === batchId)
  if (!part) {
    part = {
      type: 'interrupt_card',
      batchId,
      messageId: content.messageId || message.messageId,
      interrupts: content.interrupts || [],
      status: 'PENDING',
      error: ''
    }
    message.parts.push(part)
  }
  if (Array.isArray(content.interrupts) && content.interrupts.length) part.interrupts = content.interrupts
  const statusByType = {
    interrupt_requested: 'PENDING',
    interrupt_resuming: 'RESUMING',
    interrupt_resumed: 'COMPLETED',
    interrupt_resume_failed: 'FAILED',
    interrupt_cancelled: 'CANCELLED'
  }
  part.status = statusByType[event.type] || content.status || part.status
  if (event.type === 'interrupt_requested') part.eventSeq = Number(event.eventSeq || 0)
  part.error = content.error || ''
  return part
}

/** 从 ask_user 的 Tool Result 中还原用户作答，供执行过程追溯问答明细。 */
export function reduceInterruptAnswers(message, event) {
  const content = event?.content || {}
  const toolCallId = content.toolCallId
  if (!toolCallId) return null
  const part = (message.parts || []).find(
    (item) =>
      item.type === 'interrupt_card' &&
      (item.interrupts || []).some((interrupt) => interrupt.payload?.toolCallId === toolCallId)
  )
  if (!part) return null
  const interrupt = (part.interrupts || []).find(
    (item) => item.payload?.toolCallId === toolCallId
  )
  let result = content.outputResult
  if (typeof result === 'string') {
    try {
      result = JSON.parse(result)
    } catch (_error) {
      return null
    }
  }
  if (!Array.isArray(result?.answers)) return null
  part.answers = [{ interruptId: interrupt.interruptId, answers: result.answers }]
  return part
}
