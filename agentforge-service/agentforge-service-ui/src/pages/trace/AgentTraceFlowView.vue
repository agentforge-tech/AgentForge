<template>
  <section class="trace-flow-view" :class="{ 'is-fullscreen': fullscreen, 'has-detail': selected }">
    <div class="canvas-shell">
      <AgentTraceGraphCanvas ref="canvas" :graph-data="positionedGraph" @node-click="select" @zoom-change="zoom = $event" />
      <div class="floating-actions">
        <el-button size="mini" @click.stop="fullscreen = !fullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</el-button>
        <el-button size="mini" @click.stop="fit">适配视图</el-button>
        <el-button size="mini" :disabled="zoom >= 180" @click.stop="zoomIn">放大</el-button>
        <el-button size="mini" :disabled="zoom <= 40" @click.stop="zoomOut">缩小</el-button>
        <button class="zoom-text" title="恢复 100%" @click.stop="resetZoom">{{ zoom }}%</button>
      </div>
      <div class="canvas-legend"><span><i class="round"></i>Round</span><span><i class="model"></i>Model</span><span><i class="tool"></i>Tool</span></div>
      <div class="canvas-hint"><i class="el-icon-mouse"></i> 拖拽节点调整位置 · 拖拽空白平移 · Ctrl/Cmd + 滚轮缩放</div>
    </div>
    <aside v-if="selected" class="node-detail">
      <header><div><small>{{ selected.type.toUpperCase() }}</small><b>{{ selected.name }}</b></div><button @click="selected = null">×</button></header>
      <div class="detail-fields">
        <section v-for="field in detailFields" :key="field.key" class="detail-field">
          <div class="detail-field__head">
            <span>{{ field.key }}</span>
            <div class="detail-field__actions">
              <button v-if="field.copyable" type="button" class="detail-field__toggle" title="复制该字段内容" @click="copy(field.value)">复制</button>
              <button v-if="isJson(field.value)" type="button" class="detail-field__toggle" @click="toggleFormat(field.key)">
                {{ isFormatted(field.key) ? '原文' : '格式化' }}
              </button>
            </div>
          </div>
          <div v-if="isJson(field.value) && isFormatted(field.key)" class="detail-json">
            <div v-for="row in jsonRows(field.value)" :key="row.key" class="detail-json__row">
              <span class="detail-json__key" :title="row.key">{{ row.key }}</span>
              <span class="detail-json__value">{{ row.value }}</span>
              <button v-if="field.copyable" type="button" class="detail-json__copy" :title="`复制 ${row.key}`" aria-label="复制参数值" @click="copy(row.value)"><i class="el-icon-document-copy"></i></button>
            </div>
          </div>
          <pre v-else class="detail-field__value">{{ field.value }}</pre>
        </section>
      </div>
    </aside>
  </section>
</template>

<script>
import AgentTraceGraphCanvas from './AgentTraceGraphCanvas.vue'
import { buildTraceGraph } from './utils/traceGraphAdapter'
import { layoutTraceGraph } from './utils/traceGraphLayout'
import { formatCompactDuration, formatTokenDetail } from './utils/traceFormat'

export default {
  name: 'AgentTraceFlowView', components: { AgentTraceGraphCanvas }, props: { round: { type: Object, required: true } },
  data: () => ({ zoom: 100, fullscreen: false, selected: null, formatted: {}, jsonCache: {} }),
  computed: {
    positionedGraph() { return layoutTraceGraph(buildTraceGraph(this.round)) },
    detailFields() {
      const node = this.selected || {}
      return [
        { key: '节点类型', value: node.type },
        { key: '执行状态', value: this.statusText(node.status) },
        { key: '执行耗时', value: this.format(node.durationMs) },
        { key: 'Token', value: this.tokenDetail(node.totalTokens) },
        { key: '开始时间', value: this.clock(node.startedAt) },
        { key: '调用参数', value: node.arguments || node.input || '-', copyable: true },
        { key: '执行结果', value: node.result || '-', copyable: true },
        { key: '异常信息', value: node.error || '-' }
      ]
    }
  },
  watch: {
    round() { this.selected = null },
    selected() { this.formatted = {}; this.jsonCache = {} }
  },
  methods: {
    select(node) { this.selected = node.source || node },
    zoomIn() { this.$refs.canvas.zoomIn() }, zoomOut() { this.$refs.canvas.zoomOut() }, resetZoom() { this.$refs.canvas.resetZoom() }, fit() { this.$refs.canvas.fit() },
    format: formatCompactDuration,
    tokenDetail: formatTokenDetail,
    clock(value) { if (!value) return '-'; return new Date(Number(value)).toLocaleTimeString('zh-CN', { hour12: false }) },
    statusText(status) { return ({ NORMAL: '成功', CONTINUE: '运行中', ERROR: '异常', CANCEL: '已取消', MAX_STEPS: '达到上限' })[status] || status || '未知' },
    async copy(text) {
      const value = text == null ? '' : String(text)
      if (!value) { this.$message.warning('无可复制内容'); return }
      try {
        await navigator.clipboard.writeText(value)
        this.$message.success('已复制')
      } catch (_e) {
        this.$message.warning('复制失败，请手动选择文本')
      }
    },
    isJson(value) { return this.parseJson(value) !== null },
    isFormatted(key) { return Boolean(this.formatted[key]) },
    toggleFormat(key) { this.$set(this.formatted, key, !this.formatted[key]) },
    parseJson(value) {
      const text = String(value == null ? '' : value).trim()
      if (!text || (text[0] !== '{' && text[0] !== '[')) return null
      try { const parsed = JSON.parse(text); return parsed !== null && typeof parsed === 'object' ? parsed : null } catch (_e) { return null }
    },
    jsonRows(value) {
      const text = String(value || '')
      if (!this.jsonCache[text]) this.$set(this.jsonCache, text, this.flattenJson(this.parseJson(text)))
      return this.jsonCache[text]
    },
    flattenJson(value, prefix = '', rows = []) {
      if (value !== null && typeof value === 'object') {
        if (Array.isArray(value)) {
          if (!value.length) rows.push({ key: prefix || '[]', value: '[]' })
          value.forEach((item, index) => this.flattenJson(item, `${prefix}[${index}]`, rows))
        } else {
          const entries = Object.entries(value)
          if (!entries.length) rows.push({ key: prefix || '{}', value: '{}' })
          entries.forEach(([key, item]) => this.flattenJson(item, prefix ? `${prefix}.${key}` : key, rows))
        }
        return rows
      }
      rows.push({ key: prefix || '值', value: value === null ? 'null' : String(value) })
      return rows
    }
  }
}
</script>

<style lang="scss" scoped>
.trace-flow-view { position:relative;height:clamp(560px,70vh,920px);min-height:560px;overflow:hidden;border:1px solid #e7eaf0;border-radius:14px;background:#f8f9fc;box-shadow:0 8px 28px rgba(30,45,80,.05);&.is-fullscreen{position:fixed;z-index:3100;inset:16px;height:auto;min-height:0;border-radius:24px;box-shadow:0 24px 70px rgba(22,34,60,.18)} }
.canvas-shell{position:relative;width:100%;height:100%;overflow:hidden}.floating-actions{position:absolute;z-index:10;bottom:14px;left:14px;display:flex;align-items:center;gap:8px;padding:8px;border:1px solid #e2e8f0;border-radius:16px;background:rgba(255,255,255,.92);box-shadow:0 12px 25px rgba(15,23,42,.08)}.zoom-text{border:0;background:transparent;color:#475569;cursor:pointer;font-size:12px;font-weight:700}.canvas-legend{position:absolute;z-index:3;top:14px;left:14px;display:flex;gap:12px;padding:7px 10px;border:1px solid rgba(218,223,232,.9);border-radius:10px;background:rgba(255,255,255,.9);color:#7f8a9c;font-size:9px;box-shadow:0 4px 14px rgba(36,50,78,.06);span{display:flex;align-items:center;gap:5px}i{width:7px;height:7px;border-radius:50%;&.round{background:#10a876}&.model{background:#8457e8}&.tool{background:#0e9ddd}&.result{background:#e99a13}}}.canvas-hint{position:absolute;z-index:3;right:14px;bottom:14px;padding:6px 9px;border-radius:7px;background:rgba(47,59,82,.74);color:#fff;font-size:9px;pointer-events:none}
.node-detail{position:absolute;z-index:20;top:16px;right:16px;width:420px;max-height:calc(100% - 32px);overflow:auto;border:1px solid #e2e8f0;border-radius:20px;background:rgba(255,255,255,.97);box-shadow:0 24px 55px rgba(15,23,42,.18);header{display:flex;align-items:flex-start;justify-content:space-between;padding:16px;border-bottom:1px solid #edf0f4;div{display:grid;gap:4px}small{color:#7e8ba2;font-size:9px;letter-spacing:.12em}b{color:#263246;font-size:15px}button{border:0;background:none;color:#8d97a7;cursor:pointer;font-size:22px}}}.detail-fields{display:grid;gap:12px;padding:16px 18px 18px}.detail-field__head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:6px;span{color:#909aaa;font-size:11px}}.detail-field__toggle{padding:2px 8px;border:1px solid #dbe4f0;border-radius:6px;background:#f6f9fd;color:#4a6b95;cursor:pointer;font-size:10px;&:hover{border-color:#8eb3e5;background:#edf4fd;color:#3b6fb6}}.detail-field__value{max-height:220px;margin:0;overflow:auto;padding:8px 10px;border:1px solid #edf0f4;border-radius:8px;background:#f8fafc;color:#344057;font:11px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;word-break:break-all}.detail-json{display:grid;max-height:260px;gap:1px;overflow:auto;padding:6px 8px;border:1px solid #edf0f4;border-radius:8px;background:#f8fafc}.detail-json__row{display:grid;grid-template-columns:minmax(72px,34%) minmax(0,1fr) auto;align-items:center;gap:8px;padding:3px 2px;border-bottom:1px dashed #eef1f6;&:last-child{border-bottom:0}}
.detail-json__copy{display:grid;width:20px;height:20px;padding:0;place-items:center;border:0;border-radius:5px;background:transparent;color:#9aa6b8;cursor:pointer;font-size:11px;&:hover{background:#eef4fd;color:#3b6fb6}}
.detail-field__actions{display:flex;align-items:center;gap:6px}.detail-json__key{overflow:hidden;color:#8491a5;font:10px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;text-overflow:ellipsis;white-space:nowrap}.detail-json__value{overflow-wrap:anywhere;color:#2f3b52;font:11px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap}
@media(max-width:900px){.trace-flow-view{height:65vh;min-height:500px}.node-detail{top:42%;right:0;width:100%;max-height:58%;border-radius:18px 18px 0 0}.canvas-hint,.canvas-legend{display:none}}
</style>
