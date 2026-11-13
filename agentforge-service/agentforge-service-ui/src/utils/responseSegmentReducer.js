const RESPONSE_SEGMENT_TYPE = 'response_segment'

function now(clock) {
  return typeof clock === 'function' ? clock() : Date.now()
}

function normalizeText(content) {
  if (content == null) return ''
  return typeof content === 'string' ? content : JSON.stringify(content)
}

function responsePhase(event) {
  return event.responsePhase || event.respPhase || ''
}

function lastOpenSegment(answer) {
  if (!answer || !Array.isArray(answer.parts)) return null
  for (let index = answer.parts.length - 1; index >= 0; index--) {
    const part = answer.parts[index]
    if (part.type === RESPONSE_SEGMENT_TYPE && part.status === 'streaming') return part
  }
  return null
}

/**
 * 纯空白 resp 不能单独开启响应片段，否则会错误切断相邻的 think。
 * 已存在打开的响应片段时仍保留空格，避免正常文本被拼接成一团。
 */
export function shouldAppendResponseSegment(answer, event) {
  return Boolean(normalizeText(event && event.content).trim()) || Boolean(lastOpenSegment(answer))
}

/**
 * 把相邻 resp 增量聚合为一个候选响应片段。
 * 片段在 resp_end 之前不预设是中间响应还是最终回答。
 */
export function appendResponseSegment(answer, event, clock) {
  if (!answer) return null
  if (!Array.isArray(answer.parts)) answer.parts = []
  if (!shouldAppendResponseSegment(answer, event)) return null
  const text = normalizeText(event && event.content)
  const roundNo = Number(event && event.roundNo) || null
  const phase = responsePhase(event || {})
  const last = answer.parts[answer.parts.length - 1]
  const compatible = last &&
    last.type === RESPONSE_SEGMENT_TYPE &&
    last.status === 'streaming' &&
    (!roundNo || !last.roundNo || last.roundNo === roundNo) &&
    (!phase || !last.responsePhase || last.responsePhase === phase)

  let part = compatible ? last : null
  if (!part) {
    const startedAt = now(clock)
    const segmentCount = answer.parts.filter((candidate) => candidate.type === RESPONSE_SEGMENT_TYPE).length
    part = {
      type: RESPONSE_SEGMENT_TYPE,
      roundNo: roundNo || segmentCount + 1,
      content: '',
      status: 'streaming',
      startedAt,
      finishedAt: null,
      duration: null,
      isFinal: false
    }
    if (phase) part.responsePhase = phase
    answer.parts.push(part)
  }
  part.content += text
  if (event && event.isFinish) closeOpenResponseSegment(answer, clock)
  return part
}

/** 结束当前响应片段，但不改变它的过程节点归属。 */
export function closeOpenResponseSegment(answer, clock) {
  const part = lastOpenSegment(answer)
  if (!part) return null
  part.status = 'complete'
  part.finishedAt = now(clock)
  part.duration = Math.max(0, part.finishedAt - (part.startedAt || part.finishedAt))
  return part
}

/**
 * 将最后一个响应片段提升为最终正文。
 * 提升后从 parts 移除，避免正文和过程区重复渲染。
 */
export function promoteLastResponseSegment(answer, clock) {
  if (!answer || !Array.isArray(answer.parts)) return null
  closeOpenResponseSegment(answer, clock)
  for (let index = answer.parts.length - 1; index >= 0; index--) {
    const part = answer.parts[index]
    if (part.type !== RESPONSE_SEGMENT_TYPE) continue
    part.isFinal = true
    answer.content = part.content || ''
    answer.parts.splice(index, 1)
    return part
  }
  return null
}

/** 异常、取消等终态只收口片段，不晋升为正文。 */
export function finishResponseSegments(answer, clock) {
  return closeOpenResponseSegment(answer, clock)
}
