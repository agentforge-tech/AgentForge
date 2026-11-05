<template>
  <div class="studio">
    <aside class="sidebar">
      <div class="sidebar-head">
        <div class="brand">
          <span class="brand-logo"><img :src="agentAvatar" :alt="agentName" /></span>
          <div>
            <div class="brand-name">{{ agentName }}</div>
          </div>
        </div>
        <el-button size="small" icon="el-icon-plus" class="new-btn" @click="newChat">新建对话</el-button>
      </div>
      <div class="sidebar-scroll">
        <div v-if="topSessions.length" class="section">
          <div class="section-title">置顶会话</div>
          <div
            v-for="item in topSessions"
            :key="'top-' + item.id"
            class="session-item session-row"
            :class="{ active: item.id === activeSessionId }"
            @click="openSession(item)"
          >
            <span class="session-body">
              <span class="session-text" :title="item.title">{{ item.title }}</span>
              <span class="session-time">{{ formatSessionTime(item) }}</span>
            </span>
            <span class="session-actions">
              <i class="el-icon-edit" title="重命名" @click.stop="renameSession(item)"></i>
              <i class="session-action-icon" title="取消置顶" @click.stop="toggleTop(item, false)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 4h6l-1 7 4 3v2H6v-2l4-3-1-7z" />
                  <path d="M12 16v5" />
                </svg>
              </i>
              <i class="el-icon-delete" title="删除" @click.stop="removeSession(item)"></i>
            </span>
          </div>
        </div>
        <div class="section">
          <div class="section-title">历史会话</div>
          <div
            v-for="item in normalSessions"
            :key="item.id"
            class="session-item session-row"
            :class="{ active: item.id === activeSessionId }"
            @click="openSession(item)"
          >
            <span class="session-body">
              <span class="session-text" :title="item.title">{{ item.title }}</span>
              <span class="session-time">{{ formatSessionTime(item) }}</span>
            </span>
            <span class="session-actions">
              <i class="el-icon-edit" title="重命名" @click.stop="renameSession(item)"></i>
              <i class="session-action-icon" title="置顶" @click.stop="toggleTop(item, true)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 4h6l-1 7 4 3v2H6v-2l4-3-1-7z" />
                  <path d="M12 16v5" />
                </svg>
              </i>
              <i class="el-icon-delete" title="删除" @click.stop="removeSession(item)"></i>
            </span>
          </div>
          <div v-if="!topSessions.length && !normalSessions.length" class="empty">
            {{ sessionLoading ? '加载中...' : '暂无历史会话' }}
          </div>
        </div>
      </div>
      <div class="sidebar-footer">
        <AppUserPanel />
      </div>
    </aside>

    <main class="workspace">
      <div class="chat-header">
        <div class="header-left">
          <div class="chat-title">{{ title }}</div>
        </div>
      </div>

      <div class="message-input-wrapper" :class="{ 'input-at-bottom': messages.length > 0 }">
      <section ref="feed" class="chat-scroll" @scroll.passive="onFeedScroll">
        <div class="chat-inner">
          <div v-if="!messages.length" class="welcome">
            <h2>{{ agentLoading ? '正在加载 Agent...' : (canChat ? '今天想解决什么问题？' : '请从已发布的 Agent 进入') }}</h2>
            <p v-if="!canChat">{{ agentError || '当前地址缺少有效的 Agent 标识，暂时无法发起正式对话。' }}</p>
          </div>

          <template v-for="(message, index) in messages">
            <div v-if="message.role === 'user'" :key="index" class="message-row user">
              <div class="user-block">
                <div class="user-bubble">{{ message.text }}</div>
                <div class="msg-actions">
                  <button class="mini-btn" title="复制" @click="copy(message.text)">
                    <i class="el-icon-document-copy"></i>
                  </button>
                </div>
              </div>
            </div>
            <div v-else :key="index" class="message-row assistant">
              <div class="bot-identity">
                <div class="bot-avatar"><img :src="agentAvatar" :alt="agentName" /></div>
                <span class="bot-name">{{ agentName }}</span>
              </div>
              <div class="bot-body">
                <AgentMessageContent :message="message" mode="elegant" />
              </div>
            </div>
          </template>
        </div>
      </section>

      <transition name="chat-scroll-btn">
        <button
          v-if="isUserScrolledUp && messages.length"
          class="chat-scroll-btn"
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

      <div class="composer-wrap">
        <div class="composer-inner">
          <div v-if="pendingAppends.length" class="append-queue" aria-live="polite">
            <div class="append-queue-head">
              <span class="append-queue-status"><i></i>后续指令</span>
              <span class="append-queue-hint">{{ pendingAppends.length }} 条等待插入</span>
            </div>
            <transition-group name="append-list" tag="div" class="append-queue-list">
              <div v-for="(item, index) in pendingAppends" :key="item.appendId" class="append-pill">
                <span class="append-pill-index">↳</span>
                <span class="append-pill-content" :title="item.content">{{ item.content }}</span>
                <span class="append-pill-waiting">第 {{ index + 1 }} 位</span>
                <button type="button" aria-label="取消这条追加指令" title="取消追加" @click="cancelAppend(item.appendId)">
                  <i class="el-icon-close"></i>
                </button>
              </div>
            </transition-group>
          </div>
          <div class="composer-box" :class="{ focus: focused, 'append-mode': running && draft.trim() }">
            <textarea ref="composerInput" v-model="draft" rows="1" :disabled="!canChat" :placeholder="canChat ? (running ? '输入补充指令，将在下一轮处理…' : '发送消息...') : '请从有效的已发布 Agent 入口访问'" @focus="focused=true" @blur="focused=false" @input="autoGrow" @compositionstart="composing=true" @compositionend="composing=false" @keydown.enter="onEnter"></textarea>
            <button
              class="send-btn"
              :class="{ stop: running && !draft.trim(), append: running && draft.trim() }"
              :disabled="!canChat || (!running && !draft.trim())"
              :title="running && draft.trim() ? '发送追加消息' : (running ? '停止' : '发送')"
              @click="running && !draft.trim() ? stop() : send()"
            >
              <svg v-if="!running || draft.trim()" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 19V5" />
                <path d="M5 12l7-7 7 7" />
              </svg>
              <span v-else class="stop-square"></span>
            </button>
          </div>
          <div class="composer-tip">AI 生成内容可能不准确，请核对重要信息</div>
        </div>
      </div>
      </div>
    </main>

  </div>
</template>

<script>
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import AppUserPanel from '../../components/AppUserPanel.vue'
import chatAutoScroll from '../../utils/chatAutoScroll'
import { postSse } from '../../utils/sse'
import {
  fetchSessionList,
  fetchTopSessionList,
  setSessionTop,
  changeSessionTitle,
  deleteSession,
  fetchSessionMessages,
  resolveSessionTitle
} from '../../utils/sessionApi'
import { replaySession } from '../../utils/sessionReplay'
import { reduceAppendEvent } from '../../utils/appendEventReducer'
import { reduceCompactEvent } from '../../utils/compactEventReducer'

/** 输入框最大高度（px），超过后内部滚动，不再继续撑高 */
const COMPOSER_MAX_HEIGHT = 120

function resolveSessionId(route) {
  return route.query.sessionId || ''
}

function resolveAid(route) {
  if (route.params && route.params.aid) return route.params.aid
  return route.query.aid || ''
}

export default {
  name: 'PublishedAgentChatPage',
  components: { AgentMessageContent, AppUserPanel },
  mixins: [chatAutoScroll],
  data: () => ({
    aid: '', agentBasic: null, agentLoading: false, agentError: '',
    draft: '', focused: false, running: false, sessionId: '', controller: null, requestVersion: 0, messages: [],
    composing: false, pendingAppends: [], deferredAppends: [], remotePollTimer: null,
    topSessions: [], normalSessions: [], sessionLoading: false,
    activeSessionId: null, sessionTitle: '',
    // 支持通过 ?sessionId= 直接恢复到某个历史会话（刷新/分享链接场景）
    pendingSessionId: ''
  }),
  computed: {
    title() {
      return this.sessionTitle || this.messages.find((m) => m.role === 'user')?.text || '新对话'
    },
    agentName() { return this.agentBasic?.name || 'AgentForge' },
    agentAvatar() { return this.agentBasic?.avatar || '/logo.png' },
    canChat() { return Boolean(this.aid && this.agentBasic && !this.agentError) }
  },
  watch: {
    '$route.query.sessionId'(value) {
      this.handleRouteSession(value || '')
    }
  },
  beforeRouteUpdate(to, from, next) {
    const aid = resolveAid(to)
    if (aid !== this.aid) {
      this.aid = aid
      this.agentBasic = null
      this.agentError = ''
      this.messages = []
      this.sessionId = ''
      this.sessionTitle = ''
      this.activeSessionId = null
      this.loadAgentBasic().then(() => this.loadSessions())
    }
    next()
  },
  created() {
    this.aid = resolveAid(this.$route)
    this.pendingSessionId = resolveSessionId(this.$route)
    this.loadAgentBasic().then(() => this.loadSessions())
  },
  methods: {
    async loadAgentBasic() {
      if (!this.aid) return
      this.agentLoading = true; this.agentError = ''
      try {
        const response = await fetch('/api/agent-config/published/' + encodeURIComponent(this.aid) + '/basic')
        if (!response.ok) throw new Error('Agent 不存在或尚未发布')
        this.agentBasic = await response.json()
      } catch (error) {
        this.agentError = error.message || 'Agent 基本信息加载失败'
        this.$message.error(this.agentError)
      } finally { this.agentLoading = false }
    },
    newChat() {
      this.stop()
      this.stopRemotePoll()
      this.messages = []
      this.sessionId = ''
      this.draft = ''
      this.sessionTitle = ''
      this.activeSessionId = null
      this.syncSessionToUrl('', { replace: false })
    },
    /** 把当前会话 ID 同步到路由：?sessionId=xxx，便于刷新、分享和前进后退 */
    syncSessionToUrl(sessionId, { replace = true } = {}) {
      const query = { ...this.$route.query }
      if (sessionId) query.sessionId = sessionId
      else delete query.sessionId
      if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return
      const location = { path: this.$route.path, query }
      const navigate = replace ? this.$router.replace(location) : this.$router.push(location)
      navigate.catch(() => {})
    },
    /** 路由中的 sessionId 变化时切换会话（浏览器前进/后退、直接访问链接） */
    async handleRouteSession(wanted) {
      if (wanted === this.sessionId) return
      if (this.running) this.detachCurrentRun()
      if (!wanted) {
        this.messages = []
        this.sessionId = ''
        this.sessionTitle = ''
        this.activeSessionId = null
        return
      }
      this.pendingSessionId = wanted
      const item = [...this.topSessions, ...this.normalSessions].find(
        (session) => session.sessionId === wanted
      )
      if (item) this.openSession(item, { syncUrl: false })
      else await this.loadSessions()
    },
    /** 会话时间展示：优先最后消息时间，其次修改/创建时间，格式 yyyy-MM-dd HH:mm */
    formatSessionTime(item) {
      const raw = item.lastMessageAt || item.modifyTime || item.createTime
      if (!raw) return ''
      const text = String(raw).replace('T', ' ').replace(/\..*$/, '').replace(/[+-]\d{2}:?\d{2}$/, '')
      return text.slice(0, 16)
    },
    async loadSessions() {
      if (!this.aid) return
      this.sessionLoading = true
      try {
        const [top, normal] = await Promise.all([
          fetchTopSessionList(this.aid),
          fetchSessionList(this.aid, 20)
        ])
        this.topSessions = top || []
        this.normalSessions = normal || []
        const wanted = this.sessionId || this.pendingSessionId
        if (wanted) {
          const current = [...this.topSessions, ...this.normalSessions].find(
            (item) => item.sessionId === wanted
          )
          if (current && this.activeSessionId !== current.id) {
            this.activeSessionId = current.id
            this.sessionTitle = current.title
            this.sessionId = current.sessionId
            this.pendingSessionId = ''
            this.syncSessionToUrl(current.sessionId)
            await this.loadMessages(current.id)
          }
        }
      } catch (error) {
        this.$message.error(error.message || '会话列表加载失败')
      } finally {
        this.sessionLoading = false
      }
    },
    async openSession(item, { syncUrl = true } = {}) {
      if (!item) return
      if (this.running) this.detachCurrentRun()
      this.activeSessionId = item.id
      this.sessionId = item.sessionId
      this.sessionTitle = item.title
      this.pendingSessionId = ''
      if (syncUrl) this.syncSessionToUrl(item.sessionId, { replace: false })
      await this.loadMessages(item.id, { restoreDeferred: true })
    },
    async loadMessages(sessionRefId, { restoreDeferred = false } = {}) {
      try {
        const records = await fetchSessionMessages(sessionRefId)
        const replay = replaySession(records)
        this.messages = replay.messages
        if (restoreDeferred) {
          this.pendingAppends = replay.pendingAppends
          this.deferredAppends = replay.deferredAppends
          this.restoreDeferredAppends()
        }
        this.syncRemoteRun(sessionRefId, replay.messages)
        this.resetScrollFollow()
        this.scroll()
      } catch (error) {
        this.$message.error(error.message || '会话消息加载失败')
      }
    },
    /**
     * 恢复“别的页面正在运行”的会话：本页没有 SSE 连接，只能按增量落库的事件快照轮询。
     * 若最后一条 AI 消息仍在运行，则复用 running 状态，让输入框自动切到 append 模式。
     */
    syncRemoteRun(sessionRefId, messages) {
      const last = [...(messages || [])].reverse().find((item) => item.role === 'assistant')
      const running = !!(last && last.status === 'running')
      if (running && !this.controller) {
        this.running = true
        this.startRemotePoll(sessionRefId)
      } else if (!this.controller) {
        this.stopRemotePoll()
        this.running = false
      }
    },
    startRemotePoll(sessionRefId) {
      if (this.remotePollTimer) return
      const expectedSessionId = this.sessionId
      const tick = async () => {
        this.remotePollTimer = null
        if (!this.running || this.sessionId !== expectedSessionId) return
        try {
          const replay = replaySession(await fetchSessionMessages(sessionRefId))
          if (this.sessionId !== expectedSessionId) return
          this.messages = replay.messages
          this.pendingAppends = replay.pendingAppends
          const last = [...replay.messages].reverse().find((item) => item.role === 'assistant')
          if (!last || last.status !== 'running') {
            this.running = false
            this.deferredAppends = replay.deferredAppends
            this.restoreDeferredAppends()
            this.loadSessions()
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
    async renameSession(item) {
      try {
        const { value } = await this.$prompt('请输入新的会话名称', '重命名会话', {
          inputValue: item.title,
          inputValidator: (val) => (val && val.trim() ? true : '会话名称不能为空')
        })
        const updated = await changeSessionTitle(item.id, value.trim())
        item.title = updated.title
        if (this.activeSessionId === item.id) this.sessionTitle = updated.title
        this.$message.success('已重命名')
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') {
          this.$message.error(error.message || '重命名失败')
        }
      }
    },
    async toggleTop(item, isTop) {
      try {
        await setSessionTop(item.id, isTop)
        await this.loadSessions()
        this.$message.success(isTop ? '已置顶' : '已取消置顶')
      } catch (error) {
        this.$message.error(error.message || '操作失败')
      }
    },
    async removeSession(item) {
      try {
        await this.$confirm('删除后该会话及其消息将不可恢复，是否继续？', '删除会话', {
          type: 'warning'
        })
      } catch (_error) {
        return
      }
      try {
        await deleteSession(item.id)
        if (this.activeSessionId === item.id) this.newChat()
        await this.loadSessions()
        this.$message.success('已删除')
      } catch (error) {
        this.$message.error(error.message || '删除失败')
      }
    },
    async copy(text) {
      if (!text) return
      try {
        await navigator.clipboard.writeText(text)
        this.$message.success('已复制')
      } catch (e) {
        this.$message.warning('复制失败，请手动选择文本')
      }
    },
    createAnswer() {
      return {
        role: 'assistant', status: 'running', parts: [], content: '', error: '',
        startedAt: Date.now(), finishedAt: null, elapsedMs: null
      }
    },
    autoGrow() {
      const el = this.$refs.composerInput
      if (!el) return
      // 高度自适应：单行保持默认高度，多行按内容增长，超过上限后内部滚动
      el.style.height = 'auto'
      const contentHeight = el.scrollHeight
      el.style.height = Math.min(contentHeight, COMPOSER_MAX_HEIGHT) + 'px'
      // 只有内容真正超出上限（需要换行滚动）时才出现滚动条
      el.style.overflowY = contentHeight > COMPOSER_MAX_HEIGHT ? 'auto' : 'hidden'
    },
    onEnter(event) {
      // 输入法组合中（如中文选词回车确认）不触发发送，仅正常上屏
      if (this.composing || event.isComposing || event.keyCode === 229) return
      // Shift + Enter：快捷换行，保留默认换行行为，不发送
      if (event.shiftKey) return
      // 普通 Enter，或 Command / Ctrl + Enter：发送
      event.preventDefault()
      this.send()
    },
    async send() {
      const text = this.draft.trim()
      if (!this.canChat) {
        this.$message.warning('当前 Agent 不存在或尚未发布')
        return
      }
      if (!text) return
      if (this.running) {
        await this.sendAppend(text)
        return
      }
      this.draft = ''
      this.$nextTick(this.autoGrow)
      this.messages.push({ role: 'user', text })
      const answer = this.createAnswer()
      this.messages.push(answer)
      const requestVersion = ++this.requestVersion
      this.running = true
      this.controller = new AbortController()
      const controller = this.controller
      this.resetScrollFollow()
      this.scroll()
      try {
        await postSse('/api/chat/stream', { aid: this.aid, sessionId: this.sessionId, message: text, isDebug: false }, {
          signal: controller.signal,
          onEvent: (event) => {
            if (requestVersion === this.requestVersion) this.handleEvent(answer, event, text)
          }
        })
      } catch (error) {
        if (error.name !== 'AbortError') {
          answer.status = 'error'
          answer.error = error.message || '请求失败'
          this.finishParts(answer)
        }
      } finally {
        if (requestVersion === this.requestVersion) {
          if (answer.status === 'running') {
            answer.status = 'success'
            this.finishParts(answer)
          }
          this.running = false
          this.controller = null
          this.restoreDeferredAppends()
          this.scroll()
          this.loadSessions()
        }
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
    handleEvent(answer, event, questionText) {
      switch (event.event) {
        case 'session':
          if (event.content) {
            this.sessionId = event.content
            this.pendingSessionId = ''
            if (!this.sessionTitle) this.sessionTitle = resolveSessionTitle(questionText)
            this.syncSessionToUrl(this.sessionId)
          }
          break
        case 'think':
          this.clearRetryNotice(answer)
          this.applyThink(answer, event)
          break
        case 'resp':
          this.clearRetryNotice(answer)
          answer.content += event.content || ''
          break
        case 'tool_call':
          this.clearRetryNotice(answer)
          this.closeOpenThink(answer)
          this.applyTool(answer, event)
          break
        case 'user_message':
          this.applyAppendEvent(answer, event)
          break
        case 'retry':
          this.applyRetryEvent(answer, event)
          break
        case 'compact':
          reduceCompactEvent(answer, event)
          break
        case 'resp_end':
          this.clearRetryNotice(answer)
          answer.status = 'success'
          this.finishParts(answer)
          break
        case 'error':
          this.clearRetryNotice(answer)
          answer.status = 'error'
          answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
          this.finishParts(answer)
          break
        default:
          break
      }
      this.scroll()
    },
    applyThink(answer, event) {
      // 只在「紧邻的上一个 part」仍是进行中的思考时追加，否则新开一段思考。
      // 这样多轮（think -> tool_call -> think ...）会各自独立成块，而不是并入上一轮。
      const last = answer.parts[answer.parts.length - 1]
      const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
      const text = event.content || ''
      if (!event.isFinish) {
        if (open) open.content += text
        else answer.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: Date.now(), duration: null })
        return
      }
      if (open) {
        if (text) open.content = text
        open.status = 'end'
        open.duration = Date.now() - (open.startedAt || Date.now())
      } else if (text) {
        answer.parts.push({ type: 'think', content: text, status: 'end', startedAt: Date.now(), duration: 0 })
      }
    },
    closeOpenThink(answer) {
      const last = answer.parts[answer.parts.length - 1]
      if (last && last.type === 'think' && last.status === 'thinking') {
        last.status = 'end'
        last.duration = Date.now() - (last.startedAt || Date.now())
      }
    },
    applyTool(answer, event) {
      const content = event.content || {}
      let part = answer.parts.find((p) => p.type === 'tool_card' && p.toolCallId && p.toolCallId === content.toolCallId)
      if (!part) {
        part = {
          type: 'tool_card', toolCallId: content.toolCallId || '', toolName: content.toolName || 'unknown',
          methodName: '', description: '', inputParams: null, outputResult: null,
          duration: null, isFinish: false, startedAt: Date.now(), finishedAt: null
        }
        answer.parts.push(part)
      }
      part.toolCallId = content.toolCallId || part.toolCallId
      part.toolName = content.toolName || part.toolName
      if (content.description) part.description = content.description
      if (content.inputParams != null) part.inputParams = content.inputParams
      if (content.outputResult != null) part.outputResult = content.outputResult
      const duration = Number(content.duration)
      if (Number.isFinite(duration) && duration > 0) part.duration = duration
      part.isFinish = !!event.isFinish
      if (part.isFinish) {
        part.finishedAt = Date.now()
        if (!(part.duration > 0)) part.duration = Math.max(100, part.finishedAt - (part.startedAt || part.finishedAt))
      }
    },
    finishParts(answer) {
      answer.parts.forEach((part) => {
        if (part.type === 'think' && part.status === 'thinking') {
          part.status = 'end'
          part.duration = Date.now() - (part.startedAt || Date.now())
        }
        if (part.type === 'tool_card' && !part.isFinish) {
          part.isFinish = true
          part.finishedAt = Date.now()
          if (!(part.duration > 0)) part.duration = part.finishedAt - (part.startedAt || part.finishedAt)
        }
      })
      if (!answer.finishedAt) answer.finishedAt = Date.now()
      if (!answer.elapsedMs) answer.elapsedMs = answer.finishedAt - (answer.startedAt || answer.finishedAt)
    },
    async sendAppend(text) {
      if (!this.sessionId) {
        this.$message.info('会话正在启动，请稍后再追加')
        return
      }
      if (this.pendingAppends.length >= 10) {
        this.$message.warning('追加队列已满，请等待前面的消息生效')
        return
      }
      try {
        const response = await fetch('/api/chat/append', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ aid: this.aid, sessionId: this.sessionId, message: text, isDebug: false })
        })
        const result = await response.json()
        if (!response.ok) throw new Error(result.message || '追加失败')
        if (!result.queued) {
          this.$message.warning(this.appendReason(result.reason))
          return
        }
        this.draft = ''
        this.$nextTick(this.autoGrow)
      } catch (error) {
        this.$message.error(error.message || '追加消息失败')
      }
    },
    async cancelAppend(appendId) {
      try {
        const response = await fetch('/api/chat/append/cancel', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ aid: this.aid, sessionId: this.sessionId, appendId, isDebug: false })
        })
        const result = await response.json()
        if (!result.canceled) this.$message.info('该消息已经生效或本轮已经结束')
      } catch (_error) {
        this.$message.error('取消追加失败')
      }
    },
    applyAppendEvent(answer, event) {
      reduceAppendEvent(this, answer, event)
    },
    /** 重试事件：只保留一条提示，deadline 用服务端给的等待时长换算到本地时间，前端自行倒计时。 */
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
      this.$nextTick(this.autoGrow)
    },
    appendReason(reason) {
      const reasons = {
        SESSION_NOT_RUNNING: '本轮对话已经结束', RUN_NOT_OPEN: '任务正在启动，请稍后再试',
        RUN_ALREADY_CLOSED: '本轮对话已经结束', QUEUE_FULL: '追加队列已满'
      }
      return reasons[reason] || reason || '追加失败'
    },
    async stop() {
      if (!this.running) return
      if (this.sessionId) fetch(`/api/chat/stop?sessionId=${encodeURIComponent(this.sessionId)}`, { method: 'POST' }).catch(() => {})
      if (this.controller) this.controller.abort()
      this.running = false
      this.stopRemotePoll()
    },
  }
}
</script>
