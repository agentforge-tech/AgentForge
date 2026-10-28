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
          type="button"
          title="回到底部"
          aria-label="回到底部"
          @click="scrollToBottom"
        >
          <i class="el-icon-arrow-down"></i>
        </button>
      </transition>

      <div class="composer-wrap">
        <div class="composer-inner">
          <div class="composer-box" :class="{ focus: focused }">
            <textarea ref="composerInput" v-model="draft" rows="1" :disabled="!canChat" :placeholder="canChat ? '发送消息...' : '请从有效的已发布 Agent 入口访问'" @focus="focused=true" @blur="focused=false" @input="autoGrow" @compositionstart="composing=true" @compositionend="composing=false" @keydown.enter="onEnter"></textarea>
            <button
              class="send-btn"
              :class="{ stop: running }"
              :disabled="!canChat || (!running && !draft.trim())"
              :title="running ? '停止' : '发送'"
              @click="running ? stop() : send()"
            >
              <svg v-if="!running" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
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
import { replaySessionMessages } from '../../utils/sessionReplay'

/** 输入框最大高度（px），超过后内部滚动，不再继续撑高 */
const COMPOSER_MAX_HEIGHT = 120

function resolveSessionId() {
  return new URLSearchParams(window.location.search).get('sessionId') || ''
}

function resolveAid() {
  const queryAid = new URLSearchParams(window.location.search).get('aid')
  if (queryAid) return queryAid
  const match = window.location.pathname.match(/^\/agent\/published\/([^/]+)\/?$/)
  return match ? decodeURIComponent(match[1]) : ''
}

export default {
  name: 'PublishedAgentChatPage',
  components: { AgentMessageContent, AppUserPanel },
  mixins: [chatAutoScroll],
  data: () => ({
    aid: resolveAid(), agentBasic: null, agentLoading: false, agentError: '',
    draft: '', focused: false, running: false, sessionId: '', controller: null, messages: [],
    composing: false,
    topSessions: [], normalSessions: [], sessionLoading: false,
    activeSessionId: null, sessionTitle: '',
    // 支持通过 ?sessionId= 直接恢复到某个历史会话（刷新/分享链接场景）
    pendingSessionId: resolveSessionId()
  }),
  computed: {
    title() {
      return this.sessionTitle || this.messages.find((m) => m.role === 'user')?.text || '新对话'
    },
    agentName() { return this.agentBasic?.name || 'AgentForge' },
    agentAvatar() { return this.agentBasic?.avatar || '/logo.png' },
    canChat() { return Boolean(this.aid && this.agentBasic && !this.agentError) }
  },
  created() {
    this.loadAgentBasic().then(() => this.loadSessions())
    window.addEventListener('popstate', this.handlePopState)
  },
  beforeDestroy() {
    window.removeEventListener('popstate', this.handlePopState)
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
      this.messages = []
      this.sessionId = ''
      this.draft = ''
      this.sessionTitle = ''
      this.activeSessionId = null
      this.syncSessionToUrl('', { replace: false })
    },
    /** 把当前会话 ID 同步到地址栏：?sessionId=xxx，便于刷新、分享和前进后退 */
    syncSessionToUrl(sessionId, { replace = true } = {}) {
      const url = new URL(window.location.href)
      if (sessionId) url.searchParams.set('sessionId', sessionId)
      else url.searchParams.delete('sessionId')
      const next = url.pathname + url.search
      if (next === window.location.pathname + window.location.search) return
      if (replace) window.history.replaceState({ sessionId: sessionId || '' }, '', next)
      else window.history.pushState({ sessionId: sessionId || '' }, '', next)
    },
    /** 浏览器前进/后退时按地址栏 sessionId 切换会话 */
    async handlePopState() {
      if (this.running) return
      const wanted = resolveSessionId()
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
      if (!item || this.running) return
      this.activeSessionId = item.id
      this.sessionId = item.sessionId
      this.sessionTitle = item.title
      this.pendingSessionId = ''
      if (syncUrl) this.syncSessionToUrl(item.sessionId, { replace: false })
      await this.loadMessages(item.id)
    },
    async loadMessages(sessionRefId) {
      try {
        const records = await fetchSessionMessages(sessionRefId)
        this.messages = replaySessionMessages(records)
        this.resetScrollFollow()
        this.scroll()
      } catch (error) {
        this.$message.error(error.message || '会话消息加载失败')
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
      if (!text || this.running) return
      this.draft = ''
      this.$nextTick(this.autoGrow)
      this.messages.push({ role: 'user', text })
      const answer = this.createAnswer()
      this.messages.push(answer)
      this.running = true
      this.controller = new AbortController()
      this.resetScrollFollow()
      this.scroll()
      try {
        await postSse('/api/chat/stream', { aid: this.aid, sessionId: this.sessionId, message: text, isDebug: false }, {
          signal: this.controller.signal,
          onEvent: (event) => this.handleEvent(answer, event, text)
        })
      } catch (error) {
        if (error.name !== 'AbortError') {
          answer.status = 'error'
          answer.error = error.message || '请求失败'
          this.finishParts(answer)
        }
      } finally {
        if (answer.status === 'running') {
          answer.status = 'success'
          this.finishParts(answer)
        }
        this.running = false
        this.controller = null
        this.scroll()
        this.loadSessions()
      }
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
          this.applyThink(answer, event)
          break
        case 'resp':
          answer.content += event.content || ''
          break
        case 'tool_call':
          this.closeOpenThink(answer)
          this.applyTool(answer, event)
          break
        case 'resp_end':
          answer.status = 'success'
          this.finishParts(answer)
          break
        case 'error':
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
    async stop() {
      if (!this.running) return
      if (this.sessionId) fetch(`/api/chat/stop?sessionId=${encodeURIComponent(this.sessionId)}`, { method: 'POST' }).catch(() => {})
      if (this.controller) this.controller.abort()
      this.running = false
    },
  }
}
</script>
