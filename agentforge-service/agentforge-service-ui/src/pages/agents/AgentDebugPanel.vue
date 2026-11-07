<template>
  <aside class="agent-config-panel agent-debug">
    <div class="agent-config-panel__head agent-debug-head">
      <div><b>调试专区</b><p>使用当前 Agent 配置进行对话验证</p></div>
      <div class="agent-debug-head-actions">
        <el-select v-model="mode" size="mini" class="agent-debug-mode" aria-label="调试展示模式">
          <el-option v-for="item in renderModes" :key="item.key" :label="item.label" :value="item.key" />
        </el-select>
        <div ref="history" class="agent-debug-history">
          <button
            class="agent-debug-clear-btn agent-debug-history-btn"
            :class="{ 'is-active': historyOpen }"
            type="button"
            title="历史调试会话"
            aria-label="历史调试会话"
            @click.stop="toggleHistory"
          >
            <i class="el-icon-time"></i>
          </button>
          <div v-if="historyOpen" class="agent-debug-history-panel" @click.stop>
            <header><b>历史调试会话</b><button type="button" aria-label="关闭" @click="closeHistory">×</button></header>
            <div class="agent-debug-history-list">
              <div v-if="historyLoading" class="agent-debug-history-tip">加载中...</div>
              <template v-else>
                <button
                  v-for="item in historySessions"
                  :key="item.id"
                  type="button"
                  :class="['agent-debug-history-item', { 'is-active': item.sessionId === sessionId }]"
                  :title="item.title"
                  @click="openSession(item)"
                >
                  <span class="agent-debug-history-title">{{ item.title || '未命名会话' }}</span>
                  <span class="agent-debug-history-time">{{ formatSessionTime(item) }}</span>
                </button>
                <div v-if="!historySessions.length" class="agent-debug-history-tip">暂无历史调试会话</div>
              </template>
            </div>
          </div>
        </div>
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
    <div v-if="pendingAppends.length" class="append-queue agent-debug-append-queue" aria-live="polite">
      <div class="append-queue-head">
        <span class="append-queue-status"><i></i>后续指令</span>
        <span class="append-queue-hint">{{ pendingAppends.length }} 条等待插入</span>
      </div>
      <transition-group name="append-list" tag="div" class="append-queue-list">
        <div v-for="(item, index) in pendingAppends" :key="item.appendId" class="append-pill">
          <span class="append-pill-index">↳</span>
          <span class="append-pill-content" :title="item.content">{{ item.content }}</span>
          <span class="append-pill-waiting">第 {{ index + 1 }} 位</span>
          <button type="button" title="取消追加" @click="cancelAppend(item.appendId)"><i class="el-icon-close"></i></button>
        </div>
      </transition-group>
    </div>
    <div class="agent-debug-input">
      <textarea
        v-model="draft"
        :placeholder="running ? '输入补充指令，将在下一轮处理…' : '输入调试问题...'"
        @compositionstart="composing = true"
        @compositionend="composing = false"
        @keydown.enter="onEnter"
      ></textarea>
      <button
        class="af-btn af-btn--primary"
        :class="{ 'af-btn--stop': running && !draft.trim() }"
        :disabled="!running && !draft.trim()"
        :title="running && draft.trim() ? '发送追加消息' : (running ? '停止' : '发送')"
        @click="running && !draft.trim() ? stop() : send()"
      >{{ running && !draft.trim() ? '停止' : (running ? '追加' : '发送') }}</button>
    </div>
  </aside>
</template>

<script>
import { RENDER_MODES, DEFAULT_RENDER_MODE } from '../../modes'
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { fetchDebugSessionList, fetchSessionMessages } from '../../utils/sessionApi'
import { replaySession } from '../../utils/sessionReplay'
import { chatTransportMode, setChatTransportMode, runChatStream, resumeChatStream } from '../../utils/chatTransport'
import { reduceAppendEvent } from '../../utils/appendEventReducer'
import { reduceCompactEvent } from '../../utils/compactEventReducer'
import { buildTodoCardPart } from '../../utils/todoEventReducer'

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
  data() { const saved = localStorage.getItem(MODE_KEY); return { mode: RENDER_MODES.some((m) => m.key === saved) ? saved : DEFAULT_RENDER_MODE, transportMode: chatTransportMode(), draft: '', composing: false, running: false, stopping: false, currentMessageId: null, controller: null, requestVersion: 0, sessionId: this.createSessionId(), messages: [], pendingAppends: [], deferredAppends: [], remotePollTimer: null, historyOpen: false, historyLoading: false, historySessions: [] } },
  computed: {
    renderModes() { return RENDER_MODES },
    displayAgentName() { return this.agentName || 'AgentForge' }
  },
  watch: {
    mode(value) { localStorage.setItem(MODE_KEY, value) },
    transportMode(value) { setChatTransportMode(value) },
    aid() { this.closeHistory() }
  },
  created() { localStorage.setItem(MODE_KEY, this.mode) },
  beforeDestroy() {
    if (this.controller) this.controller.abort()
    document.removeEventListener('click', this.onDocumentClick)
  },
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
      const text = this.draft.trim(); if (!text) return
      if (this.running) { await this.sendAppend(text); return }
      const requestVersion = ++this.requestVersion
      this.draft = ''; this.messages.push({ role: 'user', text }); const answer = { role: 'assistant', status: 'running', parts: [], content: '', error: '', startedAt: Date.now(), finishedAt: null, elapsedMs: null }; this.messages.push(answer); this.running = true; this.controller = new AbortController(); const controller = this.controller; this.resetScrollFollow(); this.scroll()
      try {
        await runChatStream({ aid: this.aid, sessionId: this.sessionId, message: text, isDebug: true }, {
          signal: controller.signal,
          onSubmitted: (task) => { this.currentMessageId = task.messageId; this.sessionId = task.sessionId },
          onEvent: (event) => this.handleEvent(answer, event)
        })
      } catch (e) {
        if (e.name !== 'AbortError') { answer.status = 'error'; answer.error = e.message || '调试失败'; this.finishParts(answer) }
      } finally {
        if (answer.status === 'running') { answer.status = 'success'; this.finishParts(answer) }
        if (requestVersion === this.requestVersion) { this.running = false; this.currentMessageId = null; this.controller = null; this.restoreDeferredAppends(); this.scroll() }
        if (this.historyOpen) this.loadHistory()
      }
    },
    async stop() {
      if (!this.running || this.stopping) return
      this.stopping = true
      this.scroll()
      let result = null
      try {
        // 同步等待后端真正停止：停止接口会轮询消息到终态后返回权威状态，不能一点击就直接显示已取消。
        if (this.transportMode === 'detached' && this.currentMessageId) {
          const response = await fetch(`/api/chat/tasks/stop?messageId=${encodeURIComponent(this.currentMessageId)}`, { method: 'POST' })
          result = await response.json().catch(() => null)
        } else if (this.sessionId) {
          const response = await fetch(`/api/chat/stop?sessionId=${encodeURIComponent(this.sessionId)}`, { method: 'POST' })
          result = await response.json().catch(() => null)
        }
      } catch (_error) {
        result = null
      }
      this.applyStopResult(result)
      if (this.controller) this.controller.abort()
      this.running = false
      this.stopping = false
      this.stopRemotePoll()
    },
    /** 根据停止接口返回的真实终态渲染，未拿到状态时才按取消兜底。 */
    applyStopResult(result) {
      // 停止等待期间 SSE 可能已把消息标成 success/error，这里以本轮最后一条 assistant 消息为准。
      const answer = [...this.messages].reverse().find((m) => m.role === 'assistant')
      if (!answer) return
      const name = result && result.statusName
      if (name === 'SUCCEEDED') answer.status = 'success'
      else if (name === 'FAILED') { answer.status = 'error'; if (!answer.error) answer.error = '生成失败' }
      else answer.status = 'cancelled'
      this.finishParts(answer)
    },
    async sendAppend(text) {
      try {
        const response = await fetch('/api/chat/append', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ aid: this.aid, sessionId: this.sessionId, message: text, isDebug: true })
        })
        const result = await response.json()
        if (!result.queued) { this.$message.warning(result.reason || '追加失败'); return }
        this.draft = ''
      } catch (error) {
        this.$message.error(error.message || '追加消息失败')
      }
    },
    async cancelAppend(appendId) {
      try {
        const response = await fetch('/api/chat/append/cancel', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ aid: this.aid, sessionId: this.sessionId, appendId, isDebug: true })
        })
        const result = await response.json()
        if (!result.canceled) this.$message.info('该指令已经生效或本轮已经结束')
      } catch (_error) { this.$message.error('取消追加失败') }
    },
    applyAppendEvent(answer, event) {
      reduceAppendEvent(this, answer, event)
    },
    applyRetryEvent(answer, event) {
      const content = event.content || {}
      const eventAt = Number(content.eventAt || 0)
      const retryAt = Number(content.retryAt || 0)
      const delaySeconds = Number(content.delaySeconds || 0)
      const waitMs = retryAt > eventAt ? retryAt - eventAt : delaySeconds * 1000
      this.clearRetryNotice(answer)
      answer.parts.push({
        type: 'retry_notice',
        currentStep: content.currentStep,
        attempt: content.retryCount || 1,
        maxRetries: content.maxRetries || 1,
        durationSeconds: delaySeconds || Math.ceil(waitMs / 1000),
        deadline: Date.now() + Math.max(0, waitMs)
      })
    },
    clearRetryNotice(answer) {
      if (!answer || !Array.isArray(answer.parts)) return
      if (!answer.parts.some((part) => part.type === 'retry_notice')) return
      answer.parts = answer.parts.filter((part) => part.type !== 'retry_notice')
    },
    restoreDeferredAppends() {
      if (!this.deferredAppends.length) return
      const text = this.deferredAppends.splice(0).map((item) => item.content).filter(Boolean).join('\n\n')
      if (!text) return
      this.draft = this.draft.trim() ? this.draft + '\n\n' + text : text
      this.$message.info('未赶上本轮的补充内容已放回输入框')
    },
    createSessionId() { return 'agent-debug-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8) },
    newSession() {
      const oldSessionId = this.sessionId
      ++this.requestVersion
      if (this.controller) this.controller.abort()
      if (this.running && this.transportMode === 'detached' && this.currentMessageId) {
        fetch(`/api/chat/tasks/stop?messageId=${encodeURIComponent(this.currentMessageId)}`, { method: 'POST' }).catch(() => {})
      } else if (this.running && oldSessionId) {
        fetch(`/api/chat/stop?sessionId=${encodeURIComponent(oldSessionId)}`, { method: 'POST' }).catch(() => {})
      }
      this.controller = null; this.currentMessageId = null; this.running = false; this.messages = []; this.pendingAppends = []; this.deferredAppends = []; this.stopRemotePoll(); this.draft = ''; this.sessionId = this.createSessionId(); this.resetScrollFollow()
      this.$message.success('已新建会话')
    },
    toggleHistory() {
      if (this.historyOpen) { this.closeHistory(); return }
      this.historyOpen = true
      this.loadHistory()
      document.addEventListener('click', this.onDocumentClick)
    },
    closeHistory() {
      this.historyOpen = false
      document.removeEventListener('click', this.onDocumentClick)
    },
    onDocumentClick(event) {
      const root = this.$refs.history
      if (!root || !root.contains(event.target)) this.closeHistory()
    },
    async loadHistory() {
      if (!this.aid) { this.historySessions = []; return }
      this.historyLoading = true
      try {
        const list = await fetchDebugSessionList(this.aid, 20)
        this.historySessions = (list || []).filter((item) => item.isDebug === 1)
      } catch (e) {
        this.$message.error(e.message || '历史会话加载失败')
      } finally {
        this.historyLoading = false
      }
    },
    async openSession(item) {
      if (!item) return
      if (this.running) this.detachCurrentRun()
      else ++this.requestVersion
      this.closeHistory()
      this.sessionId = item.sessionId
      try {
        const records = await fetchSessionMessages(item.id)
        const replay = replaySession(records, this.mode === 'debug')
        this.messages = replay.messages
        this.pendingAppends = replay.pendingAppends
        this.deferredAppends = replay.deferredAppends
        this.restoreDeferredAppends()
        this.syncRemoteRun(item.id, replay.messages)
        this.resetScrollFollow()
        this.scroll()
      } catch (e) {
        this.$message.error(e.message || '会话加载失败')
      }
    },
    syncRemoteRun(sessionRefId, messages) {
      const last = [...(messages || [])].reverse().find((item) => item.role === 'assistant')
      const running = !!(last && last.status === 'running')
      if (running && !this.controller) {
        this.running = true
        if (this.transportMode === 'detached' && last.messageId) this.resumeDetachedRun(last)
        else this.startRemotePoll(sessionRefId)
      } else if (!this.controller) {
        this.stopRemotePoll()
        this.running = false
      }
    },
    async resumeDetachedRun(answer) {
      const requestVersion = ++this.requestVersion
      this.controller = new AbortController(); this.currentMessageId = answer.messageId
      answer.parts = []; answer.content = ''; answer.error = ''; answer.lastEventSeq = 0
      try {
        await resumeChatStream(answer.messageId, 0, {
          signal: this.controller.signal,
          onEvent: (event) => {
            if (requestVersion !== this.requestVersion) return
            answer.lastEventSeq = Math.max(answer.lastEventSeq || 0, Number(event.eventSeq || 0))
            this.handleEvent(answer, event)
          }
        })
      } catch (error) {
        if (error.name !== 'AbortError') this.$message.error(error.message || '恢复流式对话失败')
      } finally {
        if (requestVersion === this.requestVersion) {
          if (answer.status === 'running') { answer.status = 'success'; this.finishParts(answer) }
          this.running = false; this.controller = null; this.currentMessageId = null
          if (this.historyOpen) this.loadHistory()
        }
      }
    },
    startRemotePoll(sessionRefId) {
      if (this.remotePollTimer) return
      const expectedSessionId = this.sessionId
      const tick = async () => {
        this.remotePollTimer = null
        if (!this.running || this.sessionId !== expectedSessionId) return
        try {
          const replay = replaySession(await fetchSessionMessages(sessionRefId), this.mode === 'debug')
          if (this.sessionId !== expectedSessionId) return
          this.messages = replay.messages
          this.pendingAppends = replay.pendingAppends
          const last = [...replay.messages].reverse().find((item) => item.role === 'assistant')
          if (!last || last.status !== 'running') {
            this.running = false
            this.deferredAppends = replay.deferredAppends
            this.restoreDeferredAppends()
            if (this.historyOpen) this.loadHistory()
            return
          }
          this.remotePollTimer = setTimeout(tick, 1500)
          this.scroll()
        } catch (_error) {
          this.remotePollTimer = setTimeout(tick, 2000)
        }
      }
      this.remotePollTimer = setTimeout(tick, 1200)
    },
    stopRemotePoll() {
      if (this.remotePollTimer) {
        clearTimeout(this.remotePollTimer)
        this.remotePollTimer = null
      }
    },
    /** 切换会话时仅脱离当前 SSE，不停止后台 Agent Run。 */
    detachCurrentRun() {
      ++this.requestVersion
      if (this.controller) this.controller.abort()
      this.controller = null
      this.running = false
      this.pendingAppends = []
      this.deferredAppends = []
      this.stopRemotePoll()
    },
    formatSessionTime(item) {
      const raw = item.lastMessageAt || item.modifyTime || item.createTime
      if (!raw) return ''
      const text = String(raw).replace('T', ' ').replace(/\..*$/, '').replace(/[+-]\d{2}:?\d{2}$/, '')
      return text.slice(0, 16)
    },
    handleEvent(answer, event) {
      // 停止受理后由 applyStopResult 统一决定终态，避免 resp_end/error 抢先写成成功/失败。
      if (this.stopping) return
      switch (event.event) {
        case 'session': if (event.content) this.sessionId = event.content; break
        case 'think': this.clearRetryNotice(answer); this.applyThink(answer, event); break
        case 'resp': this.clearRetryNotice(answer); answer.content += event.content || ''; break
        case 'tool_call': this.clearRetryNotice(answer); this.closeOpenThink(answer); this.applyTool(answer, event, this.mode === 'debug'); break
        case 'user_message': this.applyAppendEvent(answer, event); break
        case 'retry': this.applyRetryEvent(answer, event); break
        case 'compact': reduceCompactEvent(answer, event, this.mode === 'debug'); break
        case 'resp_end': this.clearRetryNotice(answer); answer.status = 'success'; this.finishParts(answer); this.running = false; this.stopRemotePoll(); break
        case 'error': this.clearRetryNotice(answer); answer.status = 'error'; answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '调试失败'); this.finishParts(answer); this.running = false; this.stopRemotePoll(); break
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
    applyTool(answer, event, detailed = false) {
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
      if (part.isFinish) {
        part.finishedAt = Date.now()
        if (!(part.duration > 0)) part.duration = Math.max(100, part.finishedAt - (part.startedAt || part.finishedAt))
        if (!detailed) {
          const todoPart = buildTodoCardPart(part)
          if (todoPart) {
            const index = answer.parts.indexOf(part)
            if (index >= 0) answer.parts.splice(index, 1, todoPart)
          }
        }
      }
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
