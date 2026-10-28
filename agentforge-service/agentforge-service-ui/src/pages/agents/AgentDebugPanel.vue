<template>
  <aside class="agent-config-panel agent-debug">
    <div class="agent-config-panel__head agent-debug-head">
      <div><b>调试专区</b><p>使用当前 Agent 配置进行对话验证</p></div>
      <div class="agent-debug-head-actions">
        <el-select v-model="mode" size="mini" class="agent-debug-mode" aria-label="调试展示模式">
          <el-option v-for="item in renderModes" :key="item.key" :label="item.label" :value="item.key" />
        </el-select>
        <button class="agent-debug-clear-btn" type="button" title="清空当前会话" aria-label="清空当前会话" @click="newSession">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20 7v5h-5" />
            <path d="M18.5 16.5A8 8 0 1 1 20 12" />
          </svg>
        </button>
      </div>
    </div>
    <div ref="feed" class="agent-debug-feed" @scroll.passive="onFeedScroll">
      <div v-if="!messages.length" class="agent-debug-empty"><span>✦</span><b>开始一次调试对话</b><p>支持调试事件时间线与发布后交互展示两种模式。</p></div>
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
          <div class="agent-debug-bot-identity">
            <div class="agent-debug-bot-avatar"><img :src="agentAvatar" :alt="displayAgentName" /></div>
            <span class="agent-debug-bot-name">{{ displayAgentName }}</span>
          </div>
          <div class="agent-debug-bot-body">
            <AgentMessageContent :message="message" :mode="mode" />
          </div>
        </div>
      </template>
    </div>
    <transition name="chat-scroll-btn">
      <button
        v-if="isUserScrolledUp && messages.length"
        class="chat-scroll-btn agent-debug-scroll-btn"
        :class="{ 'is-streaming': running }"
        type="button"
        title="回到底部"
        aria-label="回到底部"
        @click="scrollToBottom"
      >
        <span class="chat-scroll-btn__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 9l6 6 6-6" />
          </svg>
        </span>
        <span class="chat-scroll-btn__label">回到底部</span>
      </button>
    </transition>
    <div class="agent-debug-input">
      <textarea
        v-model="draft"
        placeholder="输入调试问题..."
        @compositionstart="composing = true"
        @compositionend="composing = false"
        @keydown.enter="onEnter"
      ></textarea>
      <button class="af-btn af-btn--primary" :disabled="running || !draft.trim()" @click="send">{{ running ? '运行中' : '发送' }}</button>
    </div>
  </aside>
</template>

<script>
import { RENDER_MODES, DEFAULT_RENDER_MODE } from '../../modes'
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { postSse } from '../../utils/sse'

const MODE_KEY = 'agentforge.render.mode'
export default {
  name: 'AgentDebugPanel',
  components: { AgentMessageContent },
  mixins: [chatAutoScroll],
  props: {
    aid: { type: String, required: true },
    agentName: { type: String, default: '' },
    agentAvatar: { type: String, default: '/logo.png' }
  },
  data() { const saved = localStorage.getItem(MODE_KEY); return { mode: RENDER_MODES.some((m) => m.key === saved) ? saved : DEFAULT_RENDER_MODE, draft: '', composing: false, running: false, controller: null, requestVersion: 0, sessionId: this.createSessionId(), messages: [] } },
  computed: {
    renderModes() { return RENDER_MODES },
    displayAgentName() { return this.agentName || 'AgentForge' }
  },
  watch: { mode(value) { localStorage.setItem(MODE_KEY, value) } },
  created() { localStorage.setItem(MODE_KEY, this.mode) },
  beforeDestroy() { if (this.controller) this.controller.abort() },
  methods: {
    onEnter(event) {
      // 中文等输入法正在组词时，Enter 只用于确认候选词，不发送消息。
      if (this.composing || event.isComposing || event.keyCode === 229) return
      // Shift + Enter 保留 textarea 的默认换行行为。
      if (event.shiftKey) return
      event.preventDefault()
      this.send()
    },
    async send() {
      const text = this.draft.trim(); if (!text || this.running) return
      const requestVersion = ++this.requestVersion
      this.draft = ''; this.messages.push({ role: 'user', text }); const answer = { role: 'assistant', status: 'running', parts: [], content: '', error: '', startedAt: Date.now(), finishedAt: null, elapsedMs: null }; this.messages.push(answer); this.running = true; this.controller = new AbortController(); const controller = this.controller; this.resetScrollFollow(); this.scroll()
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
      this.controller = null; this.running = false; this.messages = []; this.draft = ''; this.sessionId = this.createSessionId(); this.resetScrollFollow()
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
  }
}
</script>
