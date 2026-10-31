<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>
    <AppSidebar />
    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <el-button class="af-mobile-menu" type="text" icon="el-icon-s-fold" @click="navOpen = true" />
          <span>Agent 驾驶舱</span><span class="af-crumb__sep">/</span>
          <span class="af-crumb__current">Agent Trace 监控</span>
        </div>
        <div class="af-topbar__actions">
          <el-switch v-model="realtime" active-text="实时刷新" />
          <el-button icon="el-icon-refresh" :loading="loading" @click="refresh">刷新</el-button>
        </div>
      </header>

      <section class="trace-page">
        <aside class="trace-sessions">
          <div class="trace-sessions__head">
            <b>Agent Trace 查询</b>
            <el-select v-model="selectedAid" size="small" filterable clearable placeholder="选择 Agent" @change="onAgentChange">
              <el-option v-for="agent in agents" :key="agent.aid" :label="`${agent.name} (${agent.aid})`" :value="agent.aid" />
            </el-select>
            <el-radio-group v-model="sessionSource" size="mini" class="trace-session-source" @change="loadSessions">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button label="debug">调试</el-radio-button>
              <el-radio-button label="chat">发布</el-radio-button>
            </el-radio-group>
            <div class="trace-session-query">
              <el-input v-model.trim="sessionInput" size="small" clearable :disabled="!selectedAid" placeholder="输入 sessionId" @keyup.enter.native="openSession" />
              <el-button size="small" type="primary" :disabled="!selectedAid || !sessionInput" @click="openSession">查询</el-button>
            </div>
            <span class="trace-sessions__label">{{ selectedAid ? '该 Agent 的近期 Trace' : '请先选择 Agent' }}</span>
          </div>
          <div v-if="sessionsLoading" class="trace-empty"><i class="el-icon-loading"></i> 加载中...</div>
          <button
            v-for="item in sessions"
            v-else
            :key="item.sessionId"
            class="trace-session"
            :class="{ 'is-active': item.sessionId === selectedSessionId }"
            @click="selectSession(item.sessionId)"
          >
            <span class="trace-session__top"><code :title="item.sessionId">{{ item.sessionId }}</code><em :class="statusClass(item.status)">{{ statusText(item.status) }}</em></span>
            <span class="trace-session__foot">
              <time class="trace-session__time" :title="sessionTimeTitle(item)">{{ formatSessionTime(item) }}</time>
              <span class="trace-session__meta" :title="`${roundTitle(item)} · 总耗时 ${durationTitle(item.durationMs)} · 总 Token ${tokenTitle(item.totalTokens)}`">{{ formatDuration(item.durationMs) }} · {{ formatTokens(item.totalTokens) }} tokens · {{ roundTitle(item) }}</span>
            </span>
          </button>
          <div v-if="!sessionsLoading && !sessions.length" class="trace-empty">暂无链路数据</div>
        </aside>

        <section class="trace-main">
          <div v-if="!selectedSessionId" class="trace-welcome">
            <i class="el-icon-data-line"></i><b>选择一条 Agent Trace</b>
            <p>查看 ReAct Loop、模型调用、工具执行的流程与耗时。</p>
          </div>
          <template v-else>
            <div class="trace-header">
              <div class="trace-header__id"><code>{{ selectedSessionId }}</code></div>
              <el-radio-group v-model="viewMode" size="small">
                <el-radio-button label="flow">流程图</el-radio-button>
                <el-radio-button label="flame">火焰图</el-radio-button>
              </el-radio-group>
            </div>
            <div v-if="rounds.length > 1" class="trace-round-tabs">
              <button v-for="round in rounds" :key="round.questionRound" :class="{ active: round === selectedRound }" @click="selectedRound = round">
                Round {{ round.questionRound }}<span>{{ statusText(round.status) }}</span>
              </button>
            </div>
            <div v-if="selectedRound" class="trace-stats">
              <div :title="durationTitle(selectedRound.durationMs)"><span>总耗时</span><b>{{ formatDuration(selectedRound.durationMs) }}</b></div>
              <div :title="durationTitle(selectedRound.modelDurationMs)"><span>总模型耗时</span><b>{{ formatDuration(selectedRound.modelDurationMs) }}</b></div>
              <div :title="durationTitle(selectedRound.toolDurationMs)"><span>总工具耗时</span><b>{{ formatDuration(selectedRound.toolDurationMs) }}</b></div>
              <div><span>Loop 轮次</span><b>{{ selectedRound.stepCount || 0 }}</b></div>
              <div class="trace-stat-token" :title="tokenSummary">
                <span>Token</span>
                <div class="token-values">
                  <em>输入<b>{{ formatTokens(roundTokens.input) }}</b></em>
                  <em>输出<b>{{ formatTokens(roundTokens.output) }}</b></em>
                  <em>总<b>{{ formatTokens(roundTokens.total) }}</b></em>
                  <em>缓存命中<b>{{ roundTokens.cacheRate }}</b></em>
                </div>
              </div>
            </div>
            <div v-if="traceLoading" class="trace-empty"><i class="el-icon-loading"></i> 正在加载 Trace...</div>
            <AgentTraceFlowView v-else-if="selectedRound && viewMode === 'flow'" :round="selectedRound" />
            <AgentTraceFlameView v-else-if="selectedRound" :round="selectedRound" />
          </template>
        </section>
      </section>
    </main>
  </div>
</template>

<script>
import AppSidebar from '../../components/AppSidebar.vue'
import AgentTraceFlameView from './AgentTraceFlameView.vue'
import AgentTraceFlowView from './AgentTraceFlowView.vue'
import { fetchAgents } from '../../api/agent'
import { fetchSessionTrace, fetchTraceSessions } from '../../api/trace'
import { formatCompactDuration, formatCompactNumber, formatDurationTitle, formatTokenTitle } from './utils/traceFormat'

export default {
  name: 'AgentTracePage',
  components: { AppSidebar, AgentTraceFlameView, AgentTraceFlowView },
  data: () => ({ navOpen: false, agents: [], selectedAid: '', sessionInput: '', sessionSource: 'all', sessions: [], sessionsLoading: false, selectedSessionId: '', trace: {}, traceLoading: false, selectedRound: null, viewMode: 'flow', realtime: false, timer: null }),
  computed: {
    rounds() { return this.trace.rounds || [] },
    loading() { return this.sessionsLoading || this.traceLoading },
    roundTokens() {
      const round = this.selectedRound || {}
      const input = Number(round.inputTokens || 0)
      const output = Number(round.outputTokens || 0)
      const total = Number(round.totalTokens || 0) || input + output
      const cached = Number(round.cachedTokens || 0)
      const cacheRate = input > 0 && cached > 0 ? `${Math.round((cached / input) * 100)}%` : '-'
      return { input, output, total, cached, cacheRate }
    },
    tokenSummary() {
      const tokens = this.roundTokens
      return `输入 ${this.tokenTitle(tokens.input)} · 输出 ${this.tokenTitle(tokens.output)} · 总 ${this.tokenTitle(tokens.total)} · 缓存命中 ${tokens.cacheRate}（${this.tokenTitle(tokens.cached)}）`
    }
  },
  watch: {
    realtime(value) { this.configurePolling(value) }
  },
  created() {
    const query = this.$route.query; this.selectedSessionId = query.sessionId || ''; this.sessionInput = this.selectedSessionId; this.selectedAid = query.aid || ''; this.realtime = query.realtime === '1'; this.loadAgents().then(() => { if (this.selectedAid) return this.loadSessions(); return null }).then(() => { if (this.selectedSessionId) this.loadTrace() })
  },
  beforeDestroy() { clearInterval(this.timer) },
  methods: {
    async loadAgents() { try { const result = await fetchAgents({ page: 1, pageSize: 100 }); this.agents = result.data || [] } catch (e) { this.$message.error('加载 Agent 列表失败：' + e.message) } },
    async loadSessions() { if (!this.selectedAid) { this.sessions = []; return } this.sessionsLoading = true; const debug = this.sessionSource === 'all' ? null : this.sessionSource === 'debug'; try { this.sessions = await fetchTraceSessions(this.selectedAid, debug) } catch (e) { this.$message.error(e.message) } finally { this.sessionsLoading = false } },
    onAgentChange() { this.selectedSessionId = ''; this.sessionInput = ''; this.trace = {}; this.selectedRound = null; this.loadSessions() },
    openSession() { if (!this.selectedAid || !this.sessionInput) return; this.selectSession(this.sessionInput) },
    async selectSession(sessionId) { this.sessionInput = sessionId; this.selectedSessionId = sessionId; await this.loadTrace() },
    async loadTrace(silent = false) { if (!this.selectedSessionId) return; if (!silent) this.traceLoading = true; try { const selected = this.selectedRound && this.selectedRound.questionRound; const trace = await fetchSessionTrace(this.selectedSessionId); if (this.selectedAid && trace.aid !== this.selectedAid) throw new Error('sessionId 不属于当前选择的 Agent'); this.trace = trace; if (!this.selectedAid) this.selectedAid = trace.aid || ''; this.selectedRound = this.rounds.find((r) => r.questionRound === selected) || this.rounds[this.rounds.length - 1] || null } catch (e) { if (!silent) this.$message.error(e.message) } finally { this.traceLoading = false } },
    refresh() { return Promise.all([this.loadSessions(), this.loadTrace()]) },
    configurePolling(enabled) { clearInterval(this.timer); this.timer = enabled ? setInterval(() => this.loadTrace(true), 1500) : null },
    statusText(status) { return ({ NORMAL: '成功', CONTINUE: '运行中', ERROR: '异常', CANCEL: '已取消', MAX_STEPS: '达到上限' })[status] || status || '未知' },
    statusClass(status) { return `is-${String(status || '').toLowerCase()}` },
    sessionMoment(item) {
      const raw = (item && (item.modifyTime || item.createTime)) || null
      if (raw == null || raw === '') return null
      const date = typeof raw === 'number' ? new Date(raw) : new Date(String(raw).replace(/-/g, '/').replace('T', ' ').replace(/\.\d+.*$/, ''))
      return Number.isNaN(date.getTime()) ? null : date
    },
    formatSessionTime(item) {
      const date = this.sessionMoment(item)
      if (!date) return ''
      const pad = (value) => String(value).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
    },
    sessionTimeTitle(item) { const text = this.formatSessionTime(item); return text ? `最近更新：${text}` : '暂无时间记录' },
    roundTitle(item) { return `${Number(item.questionRound || 0) || 1} 轮` },
    formatDuration: formatCompactDuration,
    formatTokens: formatCompactNumber,
    durationTitle: formatDurationTitle,
    tokenTitle: formatTokenTitle
  }
}
</script>

<style lang="scss" scoped>
@use '../../styles/admin/tokens' as *;
.trace-page { display: grid; min-height: 0; flex: 1; grid-template-columns: 280px minmax(0, 1fr); background: $color-bg; }
.trace-sessions { overflow-y: auto; border-right: 1px solid $color-border; background: $color-bg; }
.trace-sessions__head { position: sticky; z-index: 2; top: 0; display: grid; gap: 12px; padding: 18px; border-bottom: 1px solid $color-border; background: $color-bg; b { color: $color-text; font-size: 14px; } }
.trace-session-query { display: flex; gap: 6px; }
.trace-session-source { display: flex; ::v-deep .el-radio-button { flex: 1; } ::v-deep .el-radio-button__inner { width: 100%; padding: 6px 0; } }
.trace-sessions__label { color: $color-text-muted; font-size: 11px; }
.trace-session { display: grid; width: 100%; gap: 7px; padding: 15px 18px; border: 0; border-bottom: 1px solid $color-border; background: transparent; color: $color-text-secondary; cursor: pointer; text-align: left; &:hover, &.is-active { background: $color-sidebar; } &.is-active { box-shadow: inset 3px 0 $color-brand; } code { overflow: hidden; color: $color-text-muted; font-size: 11px; text-overflow: ellipsis; } }
.trace-session__top { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; code { overflow: hidden; min-width: 0; color: $color-text-secondary; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; } em { flex: 0 0 auto; padding: 2px 6px; border-radius: 8px; background: #eef1f5; color: $color-text-muted; font-size: 10px; font-style: normal; &.is-normal { background: #e9f7ef; color: #24834f; } &.is-error { background: #fff0f0; color: #d44b4b; } &.is-continue { background: #eef4ff; color: $color-brand; } } }
.trace-session__meta { color: $color-text-muted; font-size: 11px; }
.trace-session__foot { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; }
.trace-session__time { flex: 0 0 auto; color: $color-text-muted; font-size: 10px; white-space: nowrap; }
.trace-main { min-width: 0; overflow: auto; padding: 15px 18px 20px; }
.trace-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; code { display: block; overflow: hidden; max-width: min(52vw, 680px); color: $color-text-secondary; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; } }
.trace-round-tabs { display: flex; overflow-x: auto; gap: 7px; margin: 11px 0 9px; button { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border: 1px solid $color-border; border-radius: 7px; background: $color-bg; color: $color-text-secondary; cursor: pointer; font-size: 11px; span { font-size: 9px; color: $color-text-muted; } &.active { border-color: $color-brand; color: $color-brand; background: rgba(76,111,219,.04); } } }
.trace-stats { display: grid; grid-template-columns: repeat(4, minmax(92px, .85fr)) minmax(216px, 1.9fr); gap: 8px; margin-bottom: 10px; div { display: flex; min-height: 42px; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 11px; border: 1px solid $color-border; border-radius: 8px; background: $color-bg; span { color: $color-text-muted; font-size: 10px; white-space: nowrap; } b { color: $color-text; font-size: 14px; white-space: nowrap; } } }
.trace-stat-token { flex-direction: column; align-items: stretch; justify-content: center; gap: 3px; .token-values { display: flex; flex-wrap: wrap; gap: 2px 12px; em { display: flex; align-items: baseline; gap: 4px; color: $color-text-muted; font-size: 10px; font-style: normal; white-space: nowrap; b { font-size: 12px; } } } }
.trace-canvas { position: relative; min-height: 520px; padding: 28px; border: 1px solid $color-border; border-radius: 9px; background: $color-bg; }
.trace-flow { display: flex; align-items: center; flex-direction: column; }
.flow-node { display: grid; min-width: 180px; gap: 4px; padding: 12px 15px; border: 1px solid $color-border; border-radius: 8px; background: $color-bg; color: $color-text; cursor: pointer; text-align: left; box-shadow: 0 4px 14px rgba(19,33,68,.06); small { color: $color-text-muted; font-size: 9px; letter-spacing: .08em; } b { overflow: hidden; max-width: 260px; text-overflow: ellipsis; white-space: nowrap; } span { color: $color-text-muted; font-size: 11px; } &.root { border-color: #8ca8ef; background: #f3f6ff; } &.model { border-left: 3px solid #657ee8; } &.tool { border-left: 3px solid #d99a3d; } }
.flow-edge { width: 1px; height: 24px; background: $color-border; }
.flow-step { display: grid; justify-items: center; width: 100%; gap: 16px; padding: 16px; border: 1px dashed $color-border; border-radius: 10px; background: $color-bg; }
.flow-children { display: flex; align-items: stretch; justify-content: center; flex-wrap: wrap; gap: 12px; }
.trace-flame { min-width: 620px; }
.flame-axis { display: flex; justify-content: space-between; margin-left: 150px; padding-bottom: 8px; border-bottom: 1px solid $color-border; color: $color-text-muted; font-size: 10px; }
.flame-row { position: relative; height: 38px; margin-top: 6px; padding-left: 150px; label { position: absolute; left: 0; overflow: hidden; width: 138px; color: $color-text-secondary; font-size: 11px; line-height: 30px; text-overflow: ellipsis; white-space: nowrap; } &--child label { padding-left: 14px; } }
.flame-bar { position: absolute; top: 0; overflow: hidden; height: 30px; padding: 0 8px; border: 0; border-radius: 4px; color: #26334f; cursor: pointer; font-size: 10px; text-align: left; text-overflow: ellipsis; white-space: nowrap; &.root { background: #c7d4ff; } &.step { background: #dbe3ff; } &.model { background: #aebfff; } &.tool { background: #f7d5a0; } }
.trace-detail { position: absolute; z-index: 3; top: 14px; right: 14px; width: 310px; max-height: calc(100% - 28px); overflow: auto; border: 1px solid $color-border; border-radius: 9px; background: $color-bg; box-shadow: 0 10px 30px rgba(20,34,68,.14); }
.trace-detail__head { display: flex; align-items: center; justify-content: space-between; padding: 13px 15px; border-bottom: 1px solid $color-border; button { border: 0; background: transparent; color: $color-text-muted; cursor: pointer; font-size: 20px; } }
.trace-detail dl { display: grid; grid-template-columns: 70px minmax(0,1fr); gap: 10px; margin: 0; padding: 15px; font-size: 11px; dt { color: $color-text-muted; } dd { overflow-wrap: anywhere; margin: 0; color: $color-text; white-space: pre-wrap; } }
.trace-empty, .trace-welcome { padding: 40px 20px; color: $color-text-muted; text-align: center; }
.trace-welcome { display: flex; min-height: 420px; align-items: center; justify-content: center; flex-direction: column; i { margin-bottom: 14px; color: $color-brand; font-size: 36px; } b { color: $color-text-secondary; } p { font-size: 12px; } }
@media (max-width: 900px) { .trace-page { grid-template-columns: 1fr; } .trace-sessions { max-height: 240px; border-right: 0; border-bottom: 1px solid $color-border; } .trace-main { padding: 12px; } .trace-stats { grid-template-columns: repeat(2, 1fr); } }
</style>
