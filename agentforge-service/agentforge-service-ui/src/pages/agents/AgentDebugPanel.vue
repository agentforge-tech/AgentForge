<template>
  <aside class="agent-config-panel agent-debug">
    <div class="agent-config-panel__head agent-debug-head">
      <div><b>调试专区</b><p>使用当前 Agent 配置进行对话验证</p></div>
      <div class="agent-debug-head-actions">
        <el-select v-model="mode" size="mini" class="agent-debug-mode" aria-label="调试展示模式">
          <el-option v-for="item in renderModes" :key="item.key" :label="item.label" :value="item.key" />
        </el-select>
        <button class="agent-debug-clear-btn" type="button" title="清空并新建会话" aria-label="清空并新建会话" @click="newSession">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 7h16" />
            <path d="M9 7V4.8c0-.44.36-.8.8-.8h4.4c.44 0 .8.36.8.8V7" />
            <path d="M6.5 7l.7 12a1 1 0 0 0 1 .94h7.6a1 1 0 0 0 1-.94l.7-12" />
            <path d="M10 11v5M14 11v5" />
          </svg>
        </button>
      </div>
    </div>
    <div ref="feed" class="agent-debug-feed">
      <div v-if="!messages.length" class="agent-debug-empty"><span>✦</span><b>开始一次调试对话</b><p>支持调试事件时间线与优雅交互两种展示模式。</p></div>
      <template v-for="(message, index) in messages">
        <div v-if="message.role === 'user'" :key="index" class="agent-debug-message-row agent-debug-message-row--user">
          <div class="agent-debug-user-block">
            <div class="agent-debug-user-bubble">{{ message.text }}</div>
            <div class="agent-debug-message-actions">
              <button class="agent-debug-mini-btn" type="button" title="复制" @click="copy(message.text)"><i class="el-icon-document-copy"></i></button>
            </div>
          </div>
        </div>
        <div v-else :key="index" class="agent-debug-message-row agent-debug-message-row--assistant">
          <div class="agent-debug-bot-avatar"><img src="/logo.png" alt="AgentForge" /></div>
          <div class="agent-debug-bot-body">
            <component :is="modeComponent" :message="message" />
            <div v-if="message.status !== 'running'" class="agent-debug-message-actions">
              <button class="agent-debug-mini-btn" type="button" title="复制回答" :disabled="!message.content" @click="copy(message.content)"><i class="el-icon-document-copy"></i></button>
              <span v-if="message.status === 'error'" class="agent-debug-status-error">失败</span>
            </div>
          </div>
        </div>
      </template>
    </div>
    <div class="agent-debug-input">
      <textarea v-model="draft" placeholder="输入调试问题..." @keydown.enter.exact.prevent="send"></textarea>
      <button class="af-btn af-btn--primary" :disabled="running || !draft.trim()" @click="send">{{ running ? '运行中' : '发送' }}</button>
    </div>
  </aside>
</template>

<script>
import { RENDER_MODES, DEFAULT_RENDER_MODE, getRenderMode } from '../../modes'
import { postSse } from '../../utils/sse'

const MODE_KEY = 'agentforge.render.mode'
export default {
  name: 'AgentDebugPanel',
  props: { aid: { type: String, required: true } },
  data() { const saved = localStorage.getItem(MODE_KEY); return { mode: RENDER_MODES.some((m) => m.key === saved) ? saved : DEFAULT_RENDER_MODE, draft: '', running: false, controller: null, requestVersion: 0, sessionId: this.createSessionId(), messages: [] } },
  computed: { renderModes() { return RENDER_MODES }, modeComponent() { return getRenderMode(this.mode).component } },
  watch: { mode(value) { localStorage.setItem(MODE_KEY, value) } },
  created() { localStorage.setItem(MODE_KEY, this.mode) },
  beforeDestroy() { if (this.controller) this.controller.abort() },
  methods: {
    async send() {
      const text = this.draft.trim(); if (!text || this.running) return
      const requestVersion = ++this.requestVersion
      this.draft = ''; this.messages.push({ role: 'user', text }); const answer = { role: 'assistant', status: 'running', parts: [], content: '', error: '', startedAt: Date.now(), finishedAt: null, elapsedMs: null }; this.messages.push(answer); this.running = true; this.controller = new AbortController(); const controller = this.controller; this.scroll()
      try {
        await postSse('/api/chat/stream', { aid: this.aid, sessionId: this.sessionId, message: text, isDebug: true }, { signal: controller.signal, onEvent: (event) => this.handleEvent(answer, event) })
      } catch (e) {
        if (e.name !== 'AbortError') { answer.status = 'error'; answer.error = e.message || '调试失败'; this.finishParts(answer) }
      } finally {
        if (answer.status === 'running') { answer.status = 'success'; this.finishParts(answer) }
        if (requestVersion === this.requestVersion) { this.running = false; this.controller = null; this.scroll() }
      }
    },
    createSessionId() { return 'agent-debug-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8) },
    newSession() {
      const oldSessionId = this.sessionId
      ++this.requestVersion
      if (this.controller) this.controller.abort()
      if (this.running && oldSessionId) fetch(`/api/chat/stop?sessionId=${encodeURIComponent(oldSessionId)}`, { method: 'POST' }).catch(() => {})
      this.controller = null; this.running = false; this.messages = []; this.draft = ''; this.sessionId = this.createSessionId()
      this.$message.success('已新建会话')
    },
    handleEvent(answer, event) {
      switch (event.event) {
        case 'session': if (event.content) this.sessionId = event.content; break
        case 'think': this.applyThink(answer, event); break
        case 'resp': answer.content += event.content || ''; break
        case 'tool_call': this.closeOpenThink(answer); this.applyTool(answer, event); break
        case 'resp_end': answer.status = 'success'; this.finishParts(answer); break
        case 'error': answer.status = 'error'; answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '调试失败'); this.finishParts(answer); break
        default: break
      }
      this.scroll()
    },
    applyThink(answer, event) {
      const last = answer.parts[answer.parts.length - 1]
      const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
      const text = event.content || ''
      if (!event.isFinish) {
        if (open) open.content += text
        else answer.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: Date.now(), duration: null })
      } else if (open) {
        if (text) open.content = text
        open.status = 'end'; open.duration = Date.now() - (open.startedAt || Date.now())
      } else if (text) answer.parts.push({ type: 'think', content: text, status: 'end', startedAt: Date.now(), duration: 0 })
    },
    closeOpenThink(answer) {
      const last = answer.parts[answer.parts.length - 1]
      if (last && last.type === 'think' && last.status === 'thinking') { last.status = 'end'; last.duration = Date.now() - (last.startedAt || Date.now()) }
    },
    applyTool(answer, event) {
      const content = event.content || {}
      let part = answer.parts.find((p) => p.type === 'tool_card' && p.toolCallId && p.toolCallId === content.toolCallId)
      if (!part) {
        part = { type: 'tool_card', toolCallId: content.toolCallId || '', toolName: content.toolName || 'unknown', methodName: '', description: '', inputParams: null, outputResult: null, duration: null, isFinish: false, startedAt: Date.now(), finishedAt: null }
        answer.parts.push(part)
      }
      part.toolCallId = content.toolCallId || part.toolCallId; part.toolName = content.toolName || part.toolName
      if (content.description) part.description = content.description
      if (content.inputParams != null) part.inputParams = content.inputParams
      if (content.outputResult != null) part.outputResult = content.outputResult
      const duration = Number(content.duration); if (Number.isFinite(duration) && duration > 0) part.duration = duration
      part.isFinish = !!event.isFinish
      if (part.isFinish) { part.finishedAt = Date.now(); if (!(part.duration > 0)) part.duration = Math.max(100, part.finishedAt - (part.startedAt || part.finishedAt)) }
    },
    finishParts(answer) {
      answer.parts.forEach((part) => {
        if (part.type === 'think' && part.status === 'thinking') { part.status = 'end'; part.duration = Date.now() - (part.startedAt || Date.now()) }
        if (part.type === 'tool_card' && !part.isFinish) { part.isFinish = true; part.finishedAt = Date.now(); if (!(part.duration > 0)) part.duration = part.finishedAt - (part.startedAt || part.finishedAt) }
      })
      if (!answer.finishedAt) answer.finishedAt = Date.now()
      if (!answer.elapsedMs) answer.elapsedMs = answer.finishedAt - (answer.startedAt || answer.finishedAt)
    },
    async copy(text) {
      if (!text) return
      try {
        await navigator.clipboard.writeText(text)
        this.$message.success('已复制')
      } catch (_e) {
        this.$message.warning('复制失败，请手动选择文本')
      }
    },
    scroll() { this.$nextTick(() => { if (this.$refs.feed) this.$refs.feed.scrollTop = this.$refs.feed.scrollHeight }) }
  }
}
</script>
