<template>
  <section class="trace-view" :class="{ 'is-fullscreen': fullscreen, 'has-detail': selected }">
    <header class="view-head">
      <div class="view-title"><span class="view-title__icon"><i class="el-icon-data-line"></i></span><div><b>耗时火焰图</b><span>在统一时间轴上定位推理与工具调用瓶颈</span></div></div>
      <div class="view-actions"><label>时间缩放 {{ zoom }}%</label><el-slider v-model="zoom" :min="5" :max="400" :step="1" @change="autoFit = false" /><el-button size="mini" icon="el-icon-rank" :type="autoFit ? 'primary' : ''" @click="fit">适配视图</el-button><el-button size="mini" icon="el-icon-full-screen" @click="fullscreen = !fullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</el-button></div>
    </header>
    <div class="flame-layout">
      <div class="flame-labels">
        <div class="flame-labels__inner" :style="{ transform: `translateY(${-scrollTop}px)` }">
          <div class="axis-spacer"></div>
          <div v-for="row in rows" :key="row.id" :class="['flame-label', `depth-${row.depth}`]"><i :class="`type-${row.type}`"></i><div><b>{{ row.name }}</b><span>{{ format(row.durationMs) }}</span></div></div>
        </div>
      </div>
      <div ref="viewport" class="flame-viewport" @scroll.passive="onViewportScroll">
        <div class="flame-canvas" :style="{ width: `${canvasWidth}px` }">
          <AgentTraceTimelineAxis :width="canvasWidth" :duration-ms="duration" />
          <div v-for="row in rows" :key="row.id" class="flame-track">
            <button :class="['flame-bar', row.type, { selected: selected && selected.id === row.id, error: row.status === 'ERROR' }]" :style="barStyle(row)" :title="`${row.name} ${format(row.durationMs)}`" @click="selected = row"><b>{{ row.name }}</b><span>{{ format(row.durationMs) }}</span></button>
          </div>
        </div>
      </div>
    </div>
    <aside v-if="selected" class="node-detail">
      <header><div><b>{{ selected.name }}</b><span>{{ selected.type.toUpperCase() }} · {{ selected.status || '-' }}</span></div><button @click="selected = null">×</button></header>
      <dl><template v-for="(value, key) in detail"><dt :key="`${key}-k`">{{ key }}</dt><dd :key="`${key}-v`">{{ value }}</dd></template></dl>
    </aside>
  </section>
</template>

<script>
import AgentTraceTimelineAxis from './AgentTraceTimelineAxis.vue'
import { formatCompactDuration, formatTokenDetail } from './utils/traceFormat'
export default {
  name: 'AgentTraceFlameView', components: { AgentTraceTimelineAxis }, props: { round: { type: Object, required: true } },
  data: () => ({ zoom: 100, autoFit: true, viewportWidth: 0, fullscreen: false, selected: null, scrollTop: 0 }),
  computed: {
    steps() { return (this.round.snapshots || []).filter((item) => item.snapshotKind === 'STEP') },
    duration() { return Math.max(1, Number(this.round.durationMs || 0)) },
    start() { const values = this.steps.map((item) => Number(item.startedAt || 0)).filter(Boolean); return values.length ? Math.min(...values) : 0 },
    baseWidth() { return Math.min(12000, Math.max(760, this.duration * 0.32)) },
    canvasWidth() { return this.baseWidth * this.zoom / 100 },
    rows() {
      const result = [{ id: 'root', name: `Round ${this.round.questionRound}`, type: 'root', depth: 0, startedAt: this.start, durationMs: this.duration, status: this.round.status, totalTokens: this.round.totalTokens, input: this.round.question }]
      this.steps.forEach((step) => { result.push({ ...step, id: `step-${step.step}`, name: `Step ${step.step}`, type: 'step', depth: 0 }); result.push({ id: `model-${step.step}`, name: '模型调用', type: 'model', depth: 1, startedAt: step.modelStartedAt, durationMs: step.modelDurationMs, status: step.status, totalTokens: step.totalTokens }); (step.tools || []).forEach((tool, index) => result.push({ ...tool, id: `tool-${step.step}-${tool.toolCallId || index}`, name: tool.toolName || '工具调用', type: 'tool', depth: 1 })) })
      return result
    },
    detail() { const node = this.selected || {}; return { '类型': node.type, '状态': node.status || '-', '耗时': this.format(node.durationMs), '开始时间': this.clock(node.startedAt), 'Token': this.tokenDetail(node.totalTokens), '输入': node.arguments || node.input || '-', '输出': node.result || '-', '异常': node.error || '-' } }
  },
  watch: {
    round() { this.selected = null; this.autoFit = true; this.scrollTop = 0; this.$nextTick(this.fit) },
    fullscreen() { if (this.autoFit) this.$nextTick(this.fit) },
    selected() { if (this.autoFit) this.$nextTick(this.fit) }
  },
  mounted() {
    window.addEventListener('resize', this.handleResize)
    this.$nextTick(this.fit)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize)
  },
  methods: {
    measureViewport() { const viewport = this.$refs.viewport; if (viewport) this.viewportWidth = viewport.clientWidth },
    onViewportScroll(event) { this.scrollTop = event.target.scrollTop },
    syncScrollTop() { const viewport = this.$refs.viewport; this.scrollTop = viewport ? viewport.scrollTop : 0 },
    fit() {
      this.measureViewport()
      if (!this.viewportWidth) return
      this.zoom = Math.max(5, Math.min(400, Math.floor((this.viewportWidth / this.baseWidth) * 100)))
      this.autoFit = true
      this.syncScrollTop()
    },
    handleResize() { if (this.autoFit) this.fit() },
    barStyle(row) { const left = Math.min(this.canvasWidth - 4, Math.max(0, ((Number(row.startedAt || this.start) - this.start) / this.duration) * this.canvasWidth)); const width = Math.max(4, Number(row.durationMs || 0) / this.duration * this.canvasWidth); return { left: `${left}px`, width: `${Math.max(4, Math.min(width, this.canvasWidth - left))}px` } },
    format: formatCompactDuration,
    tokenDetail: formatTokenDetail,
    clock(value) { if (!value) return '-'; return new Date(Number(value)).toLocaleTimeString('zh-CN', { hour12: false }) }
  }
}
</script>

<style lang="scss" scoped>
@use '../../styles/admin/tokens' as *;
.trace-view { position: relative; height: clamp(560px,70vh,920px); min-height: 560px; overflow: hidden; border: 1px solid #e7eaf0; border-radius: 14px; background: #f8f9fc; box-shadow: 0 8px 28px rgba(30,45,80,.05); &.is-fullscreen { position: fixed; z-index: 3100; inset: 16px; height: auto; min-height: 0; box-shadow: 0 24px 70px rgba(22,34,60,.18); } &.has-detail .flame-layout { margin-right: 340px; } }
.view-head { display: flex; height: 66px; align-items: center; justify-content: space-between; gap: 20px; padding: 0 18px; border-bottom: 1px solid #e7eaf0; background: rgba(255,255,255,.94); }
.view-title { display: flex; align-items: center; gap: 11px; &__icon { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: linear-gradient(145deg,#fff0e3,#ffe8cf); color: #c87928; font-size: 16px; } > div { display: grid; gap: 3px; } b { color: #202a3c; font-size: 14px; } span { color: #8a94a6; font-size: 11px; } }
.view-actions { display: flex; align-items: center; gap: 10px; label { color: $color-text-muted; font-size: 10px; white-space: nowrap; } .el-slider { width: 110px; } }
.flame-layout { display: grid; height: calc(100% - 66px); min-height: 0; grid-template-columns: 178px minmax(0,1fr); transition: margin .2s; }
.flame-labels { position: relative; z-index: 2; overflow: hidden; border-right: 1px solid #e5e8ee; background: #fff; }.flame-labels__inner { will-change: transform; }.axis-spacer { height: 30px; border-bottom: 1px solid #e8ebf0; }
.flame-label { display: flex; height: 46px; align-items: center; gap: 9px; padding: 0 13px; border-bottom: 1px solid #edf0f4; > i { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: #9ba8c3; &.type-model { background: #6575df; } &.type-tool { background: #d18a34; } &.type-root { background: #506fca; } } > div { display: grid; min-width: 0; gap: 2px; } b { overflow: hidden; color: #3a465b; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; } span { color: #929cac; font-size: 9px; } &.depth-1 { padding-left: 28px; } }
.flame-viewport { overflow: auto; background: #fafbfc; }.flame-canvas { position: relative; min-height: 100%; }.flame-track { position: relative; height: 46px; border-bottom: 1px solid #edf0f4; background: linear-gradient(90deg,rgba(255,255,255,.2),rgba(245,247,250,.2)); }
.flame-bar { position: absolute; top: 7px; display: flex; overflow: hidden; height: 32px; align-items: center; justify-content: space-between; gap: 8px; padding: 0 10px; border: 1px solid transparent; border-radius: 7px; color: #26334f; cursor: pointer; text-align: left; box-shadow: inset 0 1px rgba(255,255,255,.38), 0 2px 6px rgba(45,59,89,.06); transition: filter .15s, transform .15s; &:hover { z-index: 2; filter: saturate(1.12); transform: translateY(-1px); } b, span { overflow: hidden; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; } span { opacity: .7; } &.root { border-color: #aebfed; background: linear-gradient(90deg,#cbd7fa,#b9caf7); } &.step { border-color: #ccd5eb; background: linear-gradient(90deg,#e6ebf8,#d8e0f3); } &.model { border-color: #aeb9ef; background: linear-gradient(90deg,#c4cdf8,#aebaf1); } &.tool { border-color: #ebc78e; background: linear-gradient(90deg,#ffe5bb,#f3ce91); } &.selected { outline: 2px solid #5975d6; outline-offset: 1px; } &.error { border-color: #efa9a9; background: linear-gradient(90deg,#ffd7d7,#ffc0c0); } }
.node-detail { position: absolute; z-index: 5; top: 0; right: 0; bottom: 0; width: 330px; overflow: auto; border-left: 1px solid $color-border; background: $color-bg; box-shadow: -8px 0 24px rgba(23,43,77,.08); header { display: flex; align-items: flex-start; justify-content: space-between; padding: 16px; border-bottom: 1px solid $color-border; div { display: grid; gap: 4px; } b { color: $color-text; } span { color: $color-text-muted; font-size: 10px; } button { border: 0; background: transparent; color: $color-text-muted; cursor: pointer; font-size: 20px; } } dl { display: grid; grid-template-columns: 72px 1fr; gap: 10px; padding: 16px; font-size: 11px; dt { color: $color-text-muted; } dd { overflow-wrap: anywhere; margin: 0; color: $color-text; white-space: pre-wrap; } } }
@media(max-width:900px){.trace-view{height:65vh;min-height:500px}.trace-view.has-detail .flame-layout{margin-right:0}.node-detail{width:100%;top:45%;}.view-actions label,.view-actions .el-slider{display:none}}
</style>
