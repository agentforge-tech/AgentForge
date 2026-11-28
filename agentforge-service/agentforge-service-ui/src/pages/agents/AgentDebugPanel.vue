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
    <div ref="feed" class="agent-debug-feed" :class="{ 'has-interrupt': !!activeInterrupt }" @scroll.passive="onFeedScroll">
      <div v-if="!messages.length" class="agent-debug-empty"><span>✦</span><b>开始一次调试对话</b><p>支持调试事件时间线与发布后交互展示两种模式。</p></div>
      <template v-for="(message, index) in messages">
        <div v-if="message.role === 'user'" :key="index" class="agent-debug-message-row agent-debug-message-row--user">
          <div class="agent-debug-user-block">
            <div v-if="message.attachments && message.attachments.length" class="user-attachments">
              <template v-for="(item, attachmentIndex) in message.attachments">
                <img
                  v-if="isImage(item.fileType)"
                  :key="'img-' + attachmentIndex"
                  :src="item.visitUrl"
                  :alt="item.name"
                  class="bubble-image"
                  @click="previewAttachment(item)"
                />
                <a
                  v-else
                  :key="'file-' + attachmentIndex"
                  :href="item.visitUrl"
                  target="_blank"
                  rel="noopener"
                  class="bubble-file"
                >
                  <i class="el-icon-document"></i>
                  <span :title="item.name">{{ item.name }}</span>
                </a>
              </template>
            </div>
            <div v-if="message.text" class="agent-debug-user-bubble">{{ message.text }}</div>
            <div v-if="message.text" class="agent-debug-message-actions">
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
    <div class="agent-debug-composer">
      <InterruptCard v-if="activeInterrupt" class="agent-debug-interrupt" :part="activeInterrupt" compact @resumed="continueAfterResume" />
      <div class="agent-debug-composer-input">
        <div class="composer-box" :class="{ focus: focused, 'append-mode': running && draft.trim(), 'has-attachments': attachments.length, multiline: inputExpanded }">
          <div v-if="attachments.length" class="composer-pending-files">
            <div v-for="(item, index) in attachments" :key="'pending-' + item.fileId" class="pending-file-item">
              <div
                class="pending-file-card"
                :class="{ 'is-image': isImage(item.fileType) }"
                @click="previewAttachment(item)"
              >
                <img v-if="isImage(item.fileType)" :src="item.visitUrl" class="pending-file-image" :alt="item.name" />
                <span v-else-if="item.previewText" class="pending-file-preview">{{ item.previewText }}</span>
                <span v-else class="pending-file-icon"><i class="el-icon-document"></i></span>
                <span class="pending-file-name" :title="item.name">{{ item.name }}</span>
                <button type="button" class="pending-file-remove" title="移除附件" @click.stop="removeAttachment(index)">×</button>
              </div>
            </div>
          </div>
          <div class="composer-input-row" :class="{ 'is-stacked': attachments.length || inputExpanded }">
            <el-popover
              class="composer-attach-popover"
              v-model="attachmentMenuVisible"
              placement="top-start"
              trigger="click"
              :width="248"
              popper-class="attach-popover"
              :disabled="uploading || running"
            >
              <button type="button" class="attach-menu-item" @click="pickFromComputer">
                <span class="attach-menu-icon"><i class="el-icon-paperclip"></i></span>
                <span class="attach-menu-copy">
                  <span class="attach-menu-title">添加照片和文件</span>
                  <span class="attach-menu-desc">从计算机上传</span>
                </span>
              </button>
              <button
                slot="reference"
                type="button"
                class="attach-btn"
                :class="{ uploading }"
                :disabled="uploading || running"
                :title="running ? '本轮对话进行中，暂不能添加附件' : '添加照片和文件'"
              >
                <i v-if="uploading" class="el-icon-loading"></i>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </button>
            </el-popover>
            <input
              ref="fileInput"
              type="file"
              class="file-input-hidden"
              multiple
              accept="image/*,.txt,.md,.markdown,.pdf,.doc,.docx,.xls,.xlsx,.zip,.rar,.7z"
              @change="onFileInputChange"
            />
            <textarea
              ref="composerInput"
              v-model="draft"
              rows="1"
              :placeholder="running ? '输入补充指令，将在下一轮处理…' : '发送消息...'"
              @focus="focused = true"
              @blur="focused = false"
              @input="autoGrow"
              @paste="onPaste"
              @compositionstart="composing = true"
              @compositionend="composing = false"
              @keydown.enter="onEnter"
            ></textarea>
            <button
              class="send-btn"
              :class="{ stop: running && !draft.trim() && !attachments.length, append: running && draft.trim() }"
              :disabled="uploading || (!running && !draft.trim() && !attachments.length)"
              :title="running && draft.trim() ? '发送追加消息' : (running ? '停止' : '发送')"
              @click="running && !draft.trim() && !attachments.length ? stop() : send()"
            >
              <svg v-if="!running || draft.trim() || attachments.length" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 19V5" />
                <path d="M5 12l7-7 7 7" />
              </svg>
              <span v-else class="stop-square"></span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script>
import { RENDER_MODES, DEFAULT_RENDER_MODE } from '../../modes'
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import InterruptCard from '../../components/InterruptCard.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { fetchDebugSessionList, fetchSessionMessages } from '../../utils/sessionApi'
import { replaySession } from '../../utils/sessionReplay'
import { chatTransportMode, setChatTransportMode, runChatStream, resumeChatStream } from '../../utils/chatTransport'
import { uploadFile } from '../../utils/fileUpload'
import { reduceAppendEvent } from '../../utils/appendEventReducer'
import { reduceCompactEvent } from '../../utils/compactEventReducer'
import { buildTodoCardPart } from '../../utils/todoEventReducer'
import { reduceInterruptAnswers, reduceInterruptEvent } from '../../utils/interruptEventReducer'
import {
  appendResponseSegment,
  closeOpenResponseSegment,
  finishResponseSegments,
  promoteLastResponseSegment,
  shouldAppendResponseSegment
} from '../../utils/responseSegmentReducer'

const MODE_KEY = 'agentforge.render.mode'
/** 输入框最大高度（px），超过后内部滚动，不再继续撑高 */
const COMPOSER_MAX_HEIGHT = 120
export default {
  name: 'AgentDebugPanel',
  components: { AgentMessageContent, InterruptCard },
  mixins: [chatAutoScroll],
  props: {
    aid: { type: String, required: true },
    agentName: { type: String, default: '' },
    agentAvatar: { type: String, default: '/logo.png' }
  },
  data() { const saved = localStorage.getItem(MODE_KEY); return { mode: RENDER_MODES.some((m) => m.key === saved) ? saved : DEFAULT_RENDER_MODE, transportMode: chatTransportMode(), draft: '', composing: false, running: false, stopping: false, currentMessageId: null, controller: null, requestVersion: 0, sessionLoadVersion: 0, sessionId: this.createSessionId(), messages: [], pendingAppends: [], deferredAppends: [], remotePollTimer: null, historyOpen: false, historyLoading: false, historySessions: [], attachments: [], uploading: false, attachmentMenuVisible: false, inputExpanded: false, focused: false } },
  computed: {
    renderModes() { return RENDER_MODES },
    displayAgentName() { return this.agentName || 'AgentForge' },
    activeInterrupt() {
      for (let i = this.messages.length - 1; i >= 0; i -= 1) {
        const part = (this.messages[i].parts || []).find((item) => item.type === 'interrupt_card' && item.status === 'PENDING')
        if (part) return part
      }
      return null
    }
  },
  watch: {
    mode(value) { localStorage.setItem(MODE_KEY, value) },
    transportMode(value) { setChatTransportMode(value) },
    aid() { this.closeHistory() }
  },
  created() { localStorage.setItem(MODE_KEY, this.mode) },
  beforeDestroy() {
    // 关闭调试面板只脱离本地订阅，后台 Run 继续执行；再次打开从会话查询续流。
    ++this.requestVersion
    ++this.sessionLoadVersion
    if (this.controller) this.controller.abort()
    this.controller = null
    this.stopRemotePoll()
    document.removeEventListener('click', this.onDocumentClick)
  },
  methods: {
    isImage(fileType) {
      if (!fileType) return false
      const type = String(fileType).toLowerCase()
      return type.startsWith('image/') || ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'].includes(type)
    },
    isTextFile(file, fileType) {
      const type = String(fileType || (file && file.type) || '').toLowerCase()
      const name = String((file && file.name) || '').toLowerCase()
      return type.startsWith('text/') || ['txt', 'md', 'markdown'].includes(type) || /\.(txt|md|markdown)$/.test(name)
    },
    async readTextPreview(file) {
      if (!file) return ''
      try {
        const text = typeof file.text === 'function' ? await file.text() : ''
        return String(text || '').replace(/\s+/g, ' ').trim().slice(0, 300)
      } catch (_error) {
        return ''
      }
    },
    pickFromComputer() {
      this.triggerFilePick()
      this.attachmentMenuVisible = false
    },
    triggerFilePick() {
      if (this.uploading || this.running) return
      const input = this.$refs.fileInput
      if (input) input.click()
    },
    onFileInputChange(event) {
      const files = event && event.target && event.target.files ? event.target.files : []
      this.handleFiles(files)
      if (event && event.target) event.target.value = ''
    },
    onPaste(event) {
      if (!event || !event.clipboardData) return
      const items = event.clipboardData.items || []
      const files = []
      for (let i = 0; i < items.length; i += 1) {
        if (items[i].kind === 'file') {
          const file = items[i].getAsFile()
          if (file) files.push(file)
        }
      }
      if (files.length) {
        event.preventDefault()
        this.handleFiles(files)
      }
    },
    async handleFiles(fileList) {
      const files = Array.from(fileList || [])
      if (!files.length) return
      const remaining = 5 - this.attachments.length
      if (remaining <= 0) {
        this.$message.warning('单条消息最多 5 个附件')
        return
      }
      this.uploading = true
      try {
        for (const file of files.slice(0, remaining)) {
          if (file.size > 10 * 1024 * 1024) {
            this.$message.error(`${file.name} 超过 10MB`)
            continue
          }
          try {
            const result = await uploadFile(file)
            const previewText = this.isTextFile(file, result.fileType) ? await this.readTextPreview(file) : ''
            this.attachments.push({
              fileId: result.fileId,
              name: result.originFileName || result.fileName,
              fileType: result.fileType,
              fileSize: result.fileSize,
              visitUrl: result.visitUrl,
              previewText
            })
          } catch (error) {
            this.$message.error(error.message || '上传失败')
          }
        }
        if (files.length > remaining) this.$message.warning('单条消息最多 5 个附件')
      } finally {
        this.uploading = false
      }
    },
    removeAttachment(index) {
      this.attachments.splice(index, 1)
    },
    previewAttachment(item) {
      if (item && item.visitUrl) window.open(item.visitUrl, '_blank', 'noopener')
    },
    autoGrow() {
      const el = this.$refs.composerInput
      if (!el) return
      el.style.height = 'auto'
      const contentHeight = el.scrollHeight
      el.style.height = Math.min(contentHeight, COMPOSER_MAX_HEIGHT) + 'px'
      el.style.overflowY = contentHeight > COMPOSER_MAX_HEIGHT ? 'auto' : 'hidden'
      this.inputExpanded = !!this.draft.trim() && (this.draft.includes('\n') || contentHeight > 40)
    },
    onEnter(event) {
      // 中文等输入法正在组词时，Enter 只用于确认候选词，不发送消息。
      if (this.composing || event.isComposing || event.keyCode === 229) return
      // Shift + Enter 保留 textarea 的默认换行行为。
      if (event.shiftKey) return
      event.preventDefault()
      this.send()
    },
    applyInterruptStatus(answer, event) {
      if (event.type === 'interrupt_requested') {
        answer.status = 'waiting'
        this.running = true
        if (answer.messageId) this.currentMessageId = answer.messageId
      } else if (event.type === 'interrupt_resuming') {
        answer.status = 'running'
      }
    },
    applyStreamPauseState(answer) {
      if (answer.status === 'waiting') {
        this.running = true
        this.currentMessageId = answer.messageId || this.currentMessageId
        return
      }
      this.running = false
      this.currentMessageId = null
    },
    async continueAfterResume(payload) {
      if (!payload) return
      const answers = [...this.messages].reverse().filter((item) => item.role === 'assistant')
      const answer = answers.find((item) => String(item.messageId) === String(payload.messageId)) || answers[0]
      if (!answer || !answer.messageId) return
      const requestVersion = ++this.requestVersion
      if (this.controller) this.controller.abort()
      this.currentMessageId = answer.messageId
      answer.status = 'running'
      this.running = true
      this.controller = new AbortController()
      const controller = this.controller
      try {
        await resumeChatStream(answer.messageId, Number(payload.afterSeq || 0), {
          signal: controller.signal,
          onEvent: (event) => {
            if (requestVersion !== this.requestVersion) return
            answer.lastEventSeq = Math.max(answer.lastEventSeq || 0, Number(event.eventSeq || 0))
            this.handleEvent(answer, event)
          }
        })
      } catch (error) {
        if (error.name !== 'AbortError') {
          answer.status = 'error'
          answer.error = error.message || '恢复调试流式对话失败'
          this.finishParts(answer)
        }
      } finally {
        if (requestVersion === this.requestVersion) {
          if (answer.status === 'running') { promoteLastResponseSegment(answer); answer.status = 'success'; this.finishParts(answer) }
          this.applyStreamPauseState(answer)
          this.controller = null
          this.restoreDeferredAppends()
          this.scroll()
        }
        if (this.historyOpen) this.loadHistory()
      }
    },
    async send() {
      const text = this.draft.trim()
      const pendingAttachments = this.attachments.slice()
      this.attachmentMenuVisible = false
      if (!text && !pendingAttachments.length) return
      if (this.running) {
        if (pendingAttachments.length) this.$message.info('本轮对话进行中，附件将在下一轮发送')
        if (text) await this.sendAppend(text)
        return
      }
      const requestVersion = ++this.requestVersion
      this.draft = ''; this.attachments = []; this.$nextTick(this.autoGrow)
      const userMessage = { role: 'user', text }
      if (pendingAttachments.length) userMessage.attachments = pendingAttachments
      this.messages.push(userMessage)
      const answer = { role: 'assistant', status: 'running', parts: [], content: '', error: '', startedAt: Date.now(), finishedAt: null, elapsedMs: null }
      this.messages.push(answer); this.running = true; this.controller = new AbortController(); const controller = this.controller; this.resetScrollFollow(); this.scroll()
      try {
        await runChatStream({
          aid: this.aid,
          sessionId: this.sessionId,
          message: text,
          isDebug: true,
          files: pendingAttachments.map((item) => ({
            fileId: item.fileId,
            originFileName: item.name,
            fileType: item.fileType,
            visitUrl: item.visitUrl
          }))
        }, {
          signal: controller.signal,
          onSubmitted: (task) => { this.currentMessageId = task.messageId; answer.messageId = task.messageId; this.sessionId = task.sessionId },
          onEvent: (event) => this.handleEvent(answer, event)
        })
      } catch (e) {
        if (e.name !== 'AbortError') { answer.status = 'error'; answer.error = e.message || '调试失败'; this.finishParts(answer) }
      } finally {
        if (answer.status === 'running') { promoteLastResponseSegment(answer); answer.status = 'success'; this.finishParts(answer) }
        if (requestVersion === this.requestVersion) { this.applyStreamPauseState(answer); this.controller = null; this.restoreDeferredAppends(); this.scroll() }
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
      if (name === 'SUCCEEDED') { promoteLastResponseSegment(answer); answer.status = 'success' }
      else if (name === 'FAILED') { answer.status = 'error'; if (!answer.error) answer.error = '生成失败' }
      else answer.status = 'cancelled'
      this.finishParts(answer)
      if (answer.status === 'cancelled') this.cancelOpenInterrupts(answer)
    },
    /** 等待确认期间没有活动订阅，停止后需本地收口仍在等待的中断卡片。 */
    cancelOpenInterrupts(answer) {
      ;(answer.parts || []).forEach((part) => {
        if (part.type === 'interrupt_card' && (part.status === 'PENDING' || part.status === 'RESUMING')) {
          part.status = 'CANCELLED'
        }
      })
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
      // 仅脱离当前 SSE，不调用后端 stop：新建/切换调试会话不应取消正在执行的任务。
      // 真正停止由输入框的“停止”按钮触发。
      if (this.running) this.detachCurrentRun()
      else this.stopRemotePoll()
      ++this.sessionLoadVersion
      this.controller = null; this.currentMessageId = null; this.running = false; this.messages = []; this.pendingAppends = []; this.deferredAppends = []; this.draft = ''; this.attachments = []; this.attachmentMenuVisible = false; this.inputExpanded = false; this.sessionId = this.createSessionId(); this.resetScrollFollow()
      this.$nextTick(this.autoGrow)
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
      // 已在当前会话且在流式输出，重复点击无需重连，避免无谓地 abort 再重连。
      if (this.running && item.sessionId === this.sessionId) { this.closeHistory(); return }
      if (this.running) this.detachCurrentRun()
      else ++this.requestVersion
      this.closeHistory()
      const version = ++this.sessionLoadVersion
      this.sessionId = item.sessionId
      this.attachments = []
      this.attachmentMenuVisible = false
      this.inputExpanded = false
      try {
        const records = await fetchSessionMessages(item.id)
        if (version !== this.sessionLoadVersion) return
        const replay = replaySession(records, this.mode === 'debug')
        this.messages = replay.messages
        this.pendingAppends = replay.pendingAppends
        this.deferredAppends = replay.deferredAppends
        this.restoreDeferredAppends()
        this.syncRemoteRun(item.id, replay.messages)
        this.resetScrollFollow()
        this.scroll()
      } catch (e) {
        if (version !== this.sessionLoadVersion) return
        this.$message.error(e.message || '会话加载失败')
      }
    },
    syncRemoteRun(sessionRefId, messages) {
      const last = [...(messages || [])].reverse().find((item) => item.role === 'assistant')
      const running = !!(last && (last.status === 'running' || last.status === 'waiting'))
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
      if (answer.status === 'waiting') {
        this.currentMessageId = answer.messageId
        this.running = true
        return
      }
      const requestVersion = ++this.requestVersion
      this.controller = new AbortController(); this.currentMessageId = answer.messageId
      this.running = true
      // 历史事件已在会话查询的回放阶段还原为 parts；从上次事件序号继续订阅，
      // 避免整段重放，也避免清空已渲染内容后闪烁。
      const cursor = Number(answer.lastEventSeq || 0)
      if (!cursor) {
        // 没有可回放的事件（如仅靠 contentText 兜底）时，清空后从 0 重建，避免正文重复。
        answer.parts = []; answer.content = ''; answer.error = ''
      }
      try {
        await resumeChatStream(answer.messageId, cursor, {
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
          if (answer.status === 'running') { promoteLastResponseSegment(answer); answer.status = 'success'; this.finishParts(answer) }
          this.applyStreamPauseState(answer)
          this.controller = null
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
        case 'think': this.clearRetryNotice(answer); closeOpenResponseSegment(answer); this.applyThink(answer, event); break
        case 'resp':
          this.clearRetryNotice(answer)
          if (shouldAppendResponseSegment(answer, event)) {
            this.closeOpenThink(answer)
            appendResponseSegment(answer, event)
          }
          break
        case 'tool_call': this.clearRetryNotice(answer); closeOpenResponseSegment(answer); this.closeOpenThink(answer); this.applyTool(answer, event, this.mode === 'debug'); reduceInterruptAnswers(answer, event); break
        case 'interrupt': this.clearRetryNotice(answer); closeOpenResponseSegment(answer); this.closeOpenThink(answer); reduceInterruptEvent(answer, event); this.applyInterruptStatus(answer, event); break
        case 'user_message': closeOpenResponseSegment(answer); this.applyAppendEvent(answer, event); break
        case 'retry': closeOpenResponseSegment(answer); this.applyRetryEvent(answer, event); break
        case 'compact': closeOpenResponseSegment(answer); reduceCompactEvent(answer, event, this.mode === 'debug'); break
        case 'resp_end': this.clearRetryNotice(answer); promoteLastResponseSegment(answer); answer.status = 'success'; this.finishParts(answer); this.running = false; this.stopRemotePoll(); break
        case 'error': this.clearRetryNotice(answer); finishResponseSegments(answer); answer.status = 'error'; answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '调试失败'); this.finishParts(answer); this.running = false; this.stopRemotePoll(); break
        default: break
      }
      this.scroll()
    },
    applyThink(answer, event) {
      const last = answer.parts[answer.parts.length - 1]
      const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
      const text = event.content || ''
      if (!String(text).trim() && !open) return
      if (!event.isFinish) {
        if (open) open.content += text
        else answer.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: Date.now(), duration: null })
      } else if (open) {
        if (String(text).trim()) open.content = text
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
      finishResponseSegments(answer)
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
