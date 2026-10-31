import { formatCompactDuration, formatCompactNumber } from './traceFormat'

function createNode(id, kind, title, source, extra = {}) {
  const metrics = [formatCompactDuration(source.durationMs)]
  if (source.totalTokens) metrics.push(`${formatCompactNumber(source.totalTokens)} tokens`)
  return { id, kind, title, status: source.status, metrics: metrics.join(' · '), source, ...extra }
}

export function buildTraceGraph(round) {
  const nodes = []
  const edges = []
  const groups = []
  const roundId = `round-${round.questionRound}`
  nodes.push(createNode(roundId, 'round', round.question || `Round ${round.questionRound}`, {
    ...round, type: 'round', name: round.question || `Round ${round.questionRound}`, input: round.question
  }))

  let pendingIds = [roundId]
  const steps = (round.snapshots || []).filter((item) => item.snapshotKind === 'STEP')
  steps.forEach((step) => {
    const modelId = `model-${round.questionRound}-${step.step}`
    const model = { ...step, type: 'model', name: '模型调用', durationMs: step.modelDurationMs, startedAt: step.modelStartedAt }
    nodes.push(createNode(modelId, 'model', '模型调用', model, { step: step.step }))
    pendingIds.forEach((sourceId, index) => edges.push({ id: `edge-${sourceId}-${modelId}-${index}`, source: sourceId, target: modelId, kind: 'sequence' }))

    const toolIds = []
    ;(step.tools || []).forEach((tool, index) => {
      const toolId = `tool-${round.questionRound}-${step.step}-${tool.toolCallId || index}`
      const source = { ...tool, type: 'tool', name: tool.toolName || '工具调用' }
      nodes.push(createNode(toolId, 'tool', source.name, source, { step: step.step, toolIndex: index }))
      edges.push({ id: `edge-${modelId}-${toolId}`, source: modelId, target: toolId, kind: 'tool-call' })
      toolIds.push(toolId)
    })
    groups.push({ id: `loop-${round.questionRound}-${step.step}`, step: step.step, durationMs: step.durationMs, toolCount: toolIds.length, modelId, toolIds })
    pendingIds = toolIds.length ? toolIds : [modelId]
  })

  const resultId = `result-${round.questionRound}`
  const result = { ...round, type: 'result', name: round.status === 'ERROR' ? '执行异常' : 'Round 完成' }
  nodes.push(createNode(resultId, round.status === 'ERROR' ? 'error' : 'round-result', result.name, result))
  pendingIds.forEach((sourceId, index) => edges.push({ id: `edge-${sourceId}-${resultId}-${index}`, source: sourceId, target: resultId, kind: round.status === 'ERROR' ? 'error' : 'result' }))
  return { nodes, edges, groups }
}

