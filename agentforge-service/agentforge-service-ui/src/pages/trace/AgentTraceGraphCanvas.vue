<template>
  <div ref="outer" class="trace-canvas" :class="{ 'is-panning': panning }" @pointerdown="startPan" @wheel.prevent="handleWheel">
    <div class="trace-graph" :style="graphStyle">
      <svg class="trace-edges" :width="graphData.width" :height="graphData.height">
        <defs><marker id="trace-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="context-stroke" /></marker></defs>
        <path v-for="edge in graphData.edges" :key="edge.id" :d="edgePath(edge)" :class="['trace-edge', `is-${edge.kind}`, { 'is-branch': edge.branched }]" marker-end="url(#trace-arrow)" />
      </svg>
      <section v-for="group in displayGroups" :key="group.id" class="loop-group" :style="boxStyle(group)">
        <div class="loop-label"><b>第{{ chineseNumber(group.step) }}轮 Loop</b><span>{{ format(group.durationMs) }}</span><small>模型 · {{ group.toolCount }} 个工具</small></div>
      </section>
      <button v-for="node in displayNodes" :key="node.id" :class="['trace-node', `is-${node.kind}`, { 'is-error': node.status === 'ERROR', 'is-dragging': draggingNodeId === node.id }]" :style="boxStyle(node)" @pointerdown.stop="startNodeDrag($event, node)" @click.stop="handleNodeClick(node)" @dblclick.stop="centerNode(node)">
        <div class="node-head"><span class="node-icon">{{ icon(node.kind) }}</span><b :title="node.title">{{ node.title }}</b><i :class="statusClass(node.status)"></i></div>
        <div v-if="node.kind === 'tool'" class="tool-summary"><p :title="toolInput(node)"><i>入</i>{{ toolInput(node) }}</p><p :title="toolOutput(node)"><i>出</i>{{ toolOutput(node) }}</p></div>
        <p v-else :title="description(node)">{{ description(node) }}</p>
        <div class="node-tags"><span>{{ node.metrics }}</span><em>{{ typeLabel(node.kind) }}</em></div>
      </button>
    </div>
  </div>
</template>

<script>
import { formatCompactDuration } from './utils/traceFormat'

const GROUP_PAD = { x: 58, top: 72, bottom: 26 }

export default {
  name: 'AgentTraceGraphCanvas', props: { graphData: { type: Object, required: true } },
  data: () => ({ scale: 1, tx: 0, ty: 0, panning: false, pointerId: null, panStart: null, nodeOffsets: {}, draggingNodeId: null, nodePointerId: null, nodeDragStart: null, nodeDragMoved: false }),
  computed: {
    graphStyle() { return { width: `${this.graphData.width}px`, height: `${this.graphData.height}px`, transform: `translate(${this.tx}px, ${this.ty}px) scale(${this.scale})` } },
    displayNodes() { return this.graphData.nodes.map((node) => { const offset = this.nodeOffsets[node.id] || {}; return { ...node, x: node.x + (offset.x || 0), y: node.y + (offset.y || 0) } }) },
    nodeMap() { return new Map(this.displayNodes.map((node) => [node.id, node])) },
    displayGroups() {
      return this.graphData.groups.map((group) => {
        const members = [group.modelId, ...group.toolIds].map((id) => this.nodeMap.get(id)).filter(Boolean)
        if (!members.length) return group
        const left = Math.max(0, Math.min(...members.map((node) => node.x)) - GROUP_PAD.x)
        const right = Math.min(this.graphData.width, Math.max(...members.map((node) => node.x + node.width)) + GROUP_PAD.x)
        const top = Math.max(0, Math.min(...members.map((node) => node.y)) - GROUP_PAD.top)
        const bottom = Math.min(this.graphData.height, Math.max(...members.map((node) => node.y + node.height)) + GROUP_PAD.bottom)
        return { ...group, x: left, y: top, width: right - left, height: bottom - top }
      })
    }
  },
  watch: { graphData: { deep: true, handler() { this.nodeOffsets = {}; this.$nextTick(() => this.fit()) } } },
  mounted() { window.addEventListener('pointermove', this.movePan); window.addEventListener('pointerup', this.endPan); window.addEventListener('resize', this.fit); this.$nextTick(() => this.fit()) },
  beforeDestroy() { window.removeEventListener('pointermove', this.movePan); window.removeEventListener('pointerup', this.endPan); window.removeEventListener('resize', this.fit) },
  methods: {
    boxStyle(item) { return { left: `${item.x}px`, top: `${item.y}px`, width: `${item.width}px`, height: `${item.height}px` } },
    edgePath(edge) { const source = this.findNode(edge.source); const target = this.findNode(edge.target); if (!source || !target) return ''; const sx = source.x + source.width / 2; const sy = source.y + source.height; const tx = target.x + target.width / 2; const ty = target.y; const dy = Math.max(42, (ty - sy) / 2); return `M ${sx} ${sy} C ${sx} ${sy + dy}, ${tx} ${ty - dy}, ${tx} ${ty}` },
    findNode(id) { return this.nodeMap.get(id) },
    startNodeDrag(event, node) { if (event.button !== 0) return; const offset = this.nodeOffsets[node.id] || {}; this.draggingNodeId = node.id; this.nodePointerId = event.pointerId; this.nodeDragStart = { clientX: event.clientX, clientY: event.clientY, offsetX: offset.x || 0, offsetY: offset.y || 0 }; this.nodeDragMoved = false },
    moveNodeDrag(event) {
      if (!this.draggingNodeId || event.pointerId !== this.nodePointerId) return
      const node = this.graphData.nodes.find((item) => item.id === this.draggingNodeId)
      if (!node) return
      const deltaX = (event.clientX - this.nodeDragStart.clientX) / this.scale
      const deltaY = (event.clientY - this.nodeDragStart.clientY) / this.scale
      if (Math.hypot(deltaX, deltaY) > 2) this.nodeDragMoved = true
      const minX = 12 - node.x
      const maxX = this.graphData.width - node.width - 12 - node.x
      const minY = 12 - node.y
      const maxY = this.graphData.height - node.height - 12 - node.y
      const offset = { x: this.clamp(this.nodeDragStart.offsetX + deltaX, minX, maxX), y: this.clamp(this.nodeDragStart.offsetY + deltaY, minY, maxY) }
      this.$set(this.nodeOffsets, node.id, offset)
    },
    endNodeDrag(event) { if (event.pointerId !== this.nodePointerId) return; this.draggingNodeId = null; this.nodePointerId = null; this.nodeDragStart = null },
    handleNodeClick(node) { if (this.nodeDragMoved) { this.nodeDragMoved = false; return } this.$emit('node-click', node) },
    startPan(event) { if (event.button !== 0) return; this.panning = true; this.pointerId = event.pointerId; this.panStart = { x: event.clientX, y: event.clientY, tx: this.tx, ty: this.ty } },
    movePan(event) { if (this.draggingNodeId) return this.moveNodeDrag(event); if (!this.panning || event.pointerId !== this.pointerId) return; this.tx = this.panStart.tx + event.clientX - this.panStart.x; this.ty = this.panStart.ty + event.clientY - this.panStart.y },
    endPan(event) { if (this.draggingNodeId) return this.endNodeDrag(event); if (event.pointerId !== this.pointerId) return; this.panning = false; this.pointerId = null; this.panStart = null },
    handleWheel(event) { if (event.ctrlKey || event.metaKey) { const rect = this.$refs.outer.getBoundingClientRect(); const x = event.clientX - rect.left; const y = event.clientY - rect.top; const next = this.clamp(this.scale * (event.deltaY < 0 ? 1.1 : 0.9), .4, 1.8); const ratio = next / this.scale; this.tx = x - (x - this.tx) * ratio; this.ty = y - (y - this.ty) * ratio; this.setScale(next); return } this.tx -= event.deltaX; this.ty -= event.deltaY },
    zoomIn() { this.zoomAtCenter(this.scale + .1) }, zoomOut() { this.zoomAtCenter(this.scale - .1) }, resetZoom() { this.setScale(1); this.center() },
    fit() { const outer = this.$refs.outer; if (!outer || !this.graphData.width) return; const padding = 56; const scale = this.clamp(Math.min((outer.clientWidth - padding * 2) / this.graphData.width, (outer.clientHeight - padding * 2) / this.graphData.height, 1), .4, 1); this.scale = scale; this.tx = (outer.clientWidth - this.graphData.width * scale) / 2; this.ty = padding; this.emitZoom() },
    center() { const outer = this.$refs.outer; this.tx = (outer.clientWidth - this.graphData.width * this.scale) / 2; this.ty = 40 },
    centerNode(node) { const outer = this.$refs.outer; this.tx = outer.clientWidth / 2 - (node.x + node.width / 2) * this.scale; this.ty = outer.clientHeight / 2 - (node.y + node.height / 2) * this.scale },
    zoomAtCenter(next) { const outer = this.$refs.outer; const cx = outer.clientWidth / 2; const cy = outer.clientHeight / 2; const scale = this.clamp(next, .4, 1.8); const ratio = scale / this.scale; this.tx = cx - (cx - this.tx) * ratio; this.ty = cy - (cy - this.ty) * ratio; this.setScale(scale) },
    setScale(value) { this.scale = this.clamp(value, .4, 1.8); this.emitZoom() }, emitZoom() { this.$emit('zoom-change', Math.round(this.scale * 100)) }, clamp(value, min, max) { return Math.min(max, Math.max(min, value)) },
    icon(kind) { return ({ round: 'U', model: 'AI', tool: 'T', 'tool-result': 'R', 'tool-error': '!', 'round-result': '✓', error: '!' })[kind] || '?' },
    typeLabel(kind) { return ({ round: '用户请求', model: '模型', tool: '工具调用', 'tool-result': '工具结果', 'tool-error': '异常结果', 'round-result': '完成', error: '异常' })[kind] || kind },
    statusClass(status) { return `status-${String(status || '').toLowerCase()}` },
    description(node) { const source = node.source || {}; if (node.kind === 'round') return '用户发起请求'; if (node.kind === 'model') return `第 ${node.step} 轮模型推理`; if (node.kind === 'tool') return this.short(source.arguments) || '工具调用参数'; if (node.kind === 'tool-result' || node.kind === 'tool-error') return this.short(source.error || source.result) || '工具执行结果'; return node.status === 'ERROR' ? '链路异常终止' : '链路执行完成' },
    toolInput(node) { return this.short((node.source || {}).arguments) || '无输入参数' }, toolOutput(node) { const source = node.source || {}; return this.short(source.error || source.result) || '暂无执行结果' },
    short(value) { if (value == null || value === '') return ''; const text = typeof value === 'string' ? value : JSON.stringify(value); return text.replace(/\s+/g, ' ').slice(0, 58) },
    chineseNumber(value) { const number = Number(value || 0); const digits = ['零','一','二','三','四','五','六','七','八','九']; if (number <= 10) return number === 10 ? '十' : digits[number]; if (number < 20) return `十${digits[number % 10]}`; if (number < 100) return `${digits[Math.floor(number / 10)]}十${number % 10 ? digits[number % 10] : ''}`; return String(number) },
    format: formatCompactDuration
  }
}
</script>

<style lang="scss" scoped>
.trace-canvas { position:relative;width:100%;height:100%;min-height:474px;overflow:hidden;background:linear-gradient(rgba(124,92,255,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(124,92,255,.045) 1px,transparent 1px);background-size:28px 28px;cursor:grab;touch-action:none;contain:strict;&.is-panning{cursor:grabbing}}.trace-graph{position:absolute;top:0;left:0;transform-origin:0 0;will-change:transform}.trace-edges{position:absolute;inset:0;overflow:visible;pointer-events:none}.trace-edge{fill:none;stroke:#aeb5c5;stroke-width:2;&.is-branch{stroke-dasharray:6 5}&.is-error{stroke:#e17878;stroke-dasharray:6 5}&.is-tool-result{stroke:#d6a04d}}
.loop-group{position:absolute;box-sizing:border-box;border:2px dashed rgba(124,92,255,.34);border-radius:28px;background:linear-gradient(180deg,rgba(124,92,255,.065),rgba(14,165,233,.035));box-shadow:0 18px 42px rgba(76,58,160,.08);pointer-events:none}.loop-label{position:absolute;top:-18px;left:18px;display:flex;height:34px;align-items:center;gap:7px;padding:0 11px;border:1px solid #ded7ff;border-radius:17px;background:#fff;box-shadow:0 10px 24px rgba(124,92,255,.15);white-space:nowrap;b{color:#5b3fd8;font-size:11px}span{padding:2px 6px;border:1px solid #dbe4ff;border-radius:9px;background:#eef2ff;color:#4f46e5;font-size:9px;font-weight:800}small{color:#667085;font-size:9px;font-weight:700}}
.trace-node{position:absolute;z-index:2;box-sizing:border-box;padding:11px 12px 16px;border:2px solid #d7dee9;border-radius:17px;background:linear-gradient(180deg,#fff,#f8fafc);color:#263246;cursor:grab;touch-action:none;text-align:left;box-shadow:0 14px 28px rgba(15,23,42,.08);transition:border-color .18s,box-shadow .18s,transform .18s;&:hover{z-index:3;box-shadow:0 20px 36px rgba(15,23,42,.15);transform:translateY(-2px)}&.is-dragging{z-index:4;cursor:grabbing;transform:none;box-shadow:0 20px 36px rgba(15,23,42,.15)}&.is-round,&.is-round-result{border-color:#b7ead4;background:linear-gradient(180deg,#fff,#f3fff9)}&.is-model{border-color:#d8ccff;background:linear-gradient(180deg,#fff,#faf7ff)}&.is-tool{padding-bottom:12px;border-color:#bee8ff;background:linear-gradient(180deg,#fff,#f3fbff);.node-tags{position:absolute;right:12px;bottom:10px;left:12px}}&.is-tool-error,&.is-error{border-color:#fecaca;background:#fff7f7}p{display:-webkit-box;overflow:hidden;margin:7px 0 8px;color:#64748b;font-size:10px;line-height:1.4;-webkit-box-orient:vertical;-webkit-line-clamp:2}}.tool-summary{display:grid;gap:3px;margin:7px 0 26px;p{display:block;overflow:hidden;margin:0;text-overflow:ellipsis;white-space:nowrap;i{display:inline-grid;width:16px;height:16px;margin-right:5px;place-items:center;border-radius:5px;background:#e7f6fd;color:#0788c1;font-size:8px;font-style:normal;font-weight:800}&+p i{background:#fff2df;color:#c47a24}}}.node-head{display:flex;align-items:center;gap:8px;b{overflow:hidden;flex:1;font-size:12px;font-weight:800;text-overflow:ellipsis;white-space:nowrap}>i{width:7px;height:7px;border-radius:50%;background:#94a3b8;&.status-normal{background:#28a66a}&.status-continue{background:#7c5ce6}&.status-error{background:#df5b5b}}}.node-icon{display:grid;width:27px;height:27px;flex:0 0 27px;place-items:center;border-radius:9px;background:#64748b;color:#fff;font-size:10px;font-weight:900}.is-model .node-icon{background:#8b5cf6}.is-tool .node-icon{background:#0ea5e9}.is-round .node-icon,.is-round-result .node-icon{background:#10b981}.is-error .node-icon{background:#e35d5d}.node-tags{display:flex;align-items:center;gap:5px;span,em{overflow:hidden;padding:3px 6px;border:1px solid #dbe4ff;border-radius:10px;background:#eef2ff;color:#4f46e5;font-size:8px;font-style:normal;font-weight:700;text-overflow:ellipsis;white-space:nowrap}em{border-color:#e2e8f0;background:#f1f5f9;color:#64748b}}
</style>
