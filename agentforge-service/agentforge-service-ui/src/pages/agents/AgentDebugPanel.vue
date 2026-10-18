<template>
  <aside class="agent-config-panel agent-debug">
    <div class="agent-config-panel__head agent-debug-head">
      <div><b>调试专区</b><p>使用当前 Agent 配置进行对话验证</p></div>
      <select v-model="mode" class="agent-debug-mode" aria-label="调试展示模式">
        <option v-for="item in renderModes" :key="item.key" :value="item.key">{{ item.label }}</option>
      </select>
    </div>
    <div ref="feed" class="agent-debug-feed">
      <div v-if="!messages.length" class="agent-debug-empty"><span>✦</span><b>开始一次调试对话</b><p>支持调试事件时间线与优雅交互两种展示模式。</p></div>
      <template v-for="(message, index) in messages">
        <div v-if="message.role === 'user'" :key="index" class="agent-debug-message agent-debug-message--user">{{ message.text }}</div>
        <div v-else :key="index" class="agent-debug-message agent-debug-message--assistant"><component :is="modeComponent" :message="message" /></div>
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
import { chatAgent } from './api'

const MODE_KEY = 'agentforge.render.mode'
export default {
  name: 'AgentDebugPanel',
  props: { aid: { type: String, required: true } },
  data() { const saved = localStorage.getItem(MODE_KEY); return { mode: RENDER_MODES.some((m) => m.key === saved) ? saved : DEFAULT_RENDER_MODE, draft: '', running: false, sessionId: 'agent-debug-' + Date.now(), messages: [] } },
  computed: { renderModes() { return RENDER_MODES }, modeComponent() { return getRenderMode(this.mode).component } },
  watch: { mode(value) { localStorage.setItem(MODE_KEY, value) } },
  methods: {
    async send() {
      const text = this.draft.trim(); if (!text || this.running) return
      this.draft = ''; this.messages.push({ role: 'user', text }); const startedAt = Date.now(); const answer = { role: 'assistant', status: 'running', parts: [], content: '', error: '', elapsedMs: null }; this.messages.push(answer); this.running = true; this.scroll()
      try { const result = await chatAgent({ aid: this.aid, sessionId: this.sessionId, message: text, isDebug: true, mode: 0 }); answer.content = result.res || ''; answer.status = result.runState === 'SUCCESS' ? 'success' : 'error'; if (answer.status === 'error' && !answer.content) answer.error = result.runStateDescription || '调试失败' } catch (e) { answer.status = 'error'; answer.error = e.message || '调试失败' } finally { answer.elapsedMs = Date.now() - startedAt; this.running = false; this.scroll() }
    },
    scroll() { this.$nextTick(() => { if (this.$refs.feed) this.$refs.feed.scrollTop = this.$refs.feed.scrollHeight }) }
  }
}
</script>
