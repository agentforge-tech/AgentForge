const SIZE = { round: [300, 106], model: [230, 106], tool: [230, 124], 'round-result': [270, 104], error: [270, 104] }
const TOOL_GAP = 56
const GROUP_PAD_X = 58
const GROUP_PAD_TOP = 72
const GROUP_PAD_BOTTOM = 26
const MODEL_TOOL_GAP = 92
const BETWEEN_GROUPS = 74

export function layoutTraceGraph(graphData) {
  const maxTools = Math.max(1, ...graphData.groups.map((group) => group.toolIds.length))
  const toolsWidth = maxTools * SIZE.tool[0] + Math.max(0, maxTools - 1) * TOOL_GAP
  const width = Math.max(1100, toolsWidth + 240)
  const centerX = width / 2
  const positions = new Map()
  const nodes = []
  const groups = []
  const nodeById = new Map(graphData.nodes.map((item) => [item.id, item]))
  const root = graphData.nodes.find((item) => item.kind === 'round')
  const result = graphData.nodes.find((item) => item.kind === 'round-result' || item.kind === 'error')
  let y = 64

  nodes.push(place(root, centerX - SIZE.round[0] / 2, y, positions))
  y += SIZE.round[1] + 84

  graphData.groups.forEach((group) => {
    const model = nodeById.get(group.modelId)
    const tools = group.toolIds.map((toolId) => nodeById.get(toolId)).filter(Boolean)
    const rowWidth = tools.length ? tools.length * SIZE.tool[0] + (tools.length - 1) * TOOL_GAP : SIZE.model[0]
    const groupWidth = Math.max(660, rowWidth + GROUP_PAD_X * 2)
    const groupX = centerX - groupWidth / 2
    const groupY = y
    const modelY = groupY + GROUP_PAD_TOP
    nodes.push(place(model, centerX - SIZE.model[0] / 2, modelY, positions))

    let groupHeight = GROUP_PAD_TOP + SIZE.model[1] + GROUP_PAD_BOTTOM
    if (tools.length) {
      const toolsY = modelY + SIZE.model[1] + MODEL_TOOL_GAP
      const startX = centerX - rowWidth / 2
      tools.forEach((tool, index) => {
        const x = startX + index * (SIZE.tool[0] + TOOL_GAP)
        nodes.push(place(tool, x, toolsY, positions))
      })
      groupHeight = toolsY - groupY + SIZE.tool[1] + GROUP_PAD_BOTTOM
    }
    groups.push({ ...group, x: groupX, y: groupY, width: groupWidth, height: groupHeight })
    y += groupHeight + BETWEEN_GROUPS
  })

  nodes.push(place(result, centerX - SIZE[result.kind][0] / 2, y, positions))
  const edges = graphData.edges.map((edge) => layoutEdge(edge, positions))
  return { nodes, edges, groups, width, height: y + SIZE[result.kind][1] + 64 }
}

function place(item, x, y, positions) {
  const [width, height] = SIZE[item.kind] || SIZE.tool
  const placed = { ...item, x, y, width, height }
  positions.set(item.id, placed)
  return placed
}

function layoutEdge(edge, positions) {
  const source = positions.get(edge.source)
  const target = positions.get(edge.target)
  if (!source || !target || Math.abs((source.x + source.width / 2) - (target.x + target.width / 2)) < 2) return edge
  const sx = source.x + source.width / 2
  const sy = source.y + source.height
  const tx = target.x + target.width / 2
  const ty = target.y
  const middleY = sy + Math.max(34, (ty - sy) / 2)
  return { ...edge, vertices: [{ x: sx, y: middleY }, { x: tx, y: middleY }], branched: true }
}

