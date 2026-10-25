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
        <div class="section">
          <div class="section-title">当前会话</div>
          <div v-if="sessionId" class="session-item active">
            <i class="el-icon-chat-dot-round"></i><span>{{ title }}</span>
          </div>
          <div v-else class="empty">暂无会话</div>
        </div>
      </div>
    </aside>

    <main class="workspace">
      <div class="chat-header">
        <div class="header-left">
          <div class="chat-title">{{ title }}</div>
          <el-tag v-if="running" size="mini" type="success"><i class="el-icon-loading"></i> 运行中</el-tag>
        </div>
      </div>

      <div class="message-input-wrapper" :class="{ 'input-at-bottom': messages.length > 0 }">
      <section ref="feed" class="chat-scroll">
        <div class="chat-inner">
          <div v-if="!messages.length" class="welcome">
            <h2>{{ agentLoading ? '正在加载 Agent...' : (canChat ? '今天想解决什么问题？' : '请从已发布的 Agent 进入') }}</h2>
            <p>{{ canChat ? ((agentBasic && agentBasic.description) || 'Agent 已就绪，可以开始对话。') : (agentError || '当前地址缺少有效的 Agent 标识，暂时无法发起正式对话。') }}</p>
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
              <div class="bot-avatar"><img :src="agentAvatar" :alt="agentName" /></div>
              <div class="bot-body">
                <AgentMessageContent :message="message" mode="elegant" />
              </div>
            </div>
          </template>
        </div>
      </section>

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
          <div class="composer-tip">Enter / ⌘ + Enter 发送 · Shift + Enter 换行 · AI 生成内容可能不准确，请核对重要信息</div>
        </div>
      </div>
      </div>
    </main>

  </div>
</template>

<script>
import AgentMessageContent from '../../components/AgentMessageContent.vue'
import { postSse } from '../../utils/sse'

function resolveAid() {
  const queryAid = new URLSearchParams(window.location.search).get('aid')
  if (queryAid) return queryAid
  const match = window.location.pathname.match(/^\/agent\/published\/([^/]+)\/?$/)
  return match ? decodeURIComponent(match[1]) : ''
}

export default {
  name: 'PublishedAgentChatPage',
  components: { AgentMessageContent },
  data: () => ({
    aid: resolveAid(), agentBasic: null, agentLoading: false, agentError: '',
    draft: '', focused: false, running: false, sessionId: '', controller: null, messages: [],
    composing: false
  }),
  computed: {
    title() { return this.messages.find((m) => m.role === 'user')?.text || '新对话' },
    agentName() { return this.agentBasic?.name || 'AgentForge' },
    agentAvatar() { return this.agentBasic?.avatar || '/logo.png' },
    canChat() { return Boolean(this.aid && this.agentBasic && !this.agentError) }
  },
  created() {
    this.loadAgentBasic()
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
    newChat() { this.stop(); this.messages = []; this.sessionId = ''; this.draft = '' },
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
      el.style.height = 'auto'
      el.style.height = Math.min(el.scrollHeight, 140) + 'px'
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
      this.scroll()
      try {
        await postSse('/api/chat/stream', { aid: this.aid, sessionId: this.sessionId, message: text, isDebug: false }, {
          signal: this.controller.signal,
          onEvent: (event) => this.handleEvent(answer, event)
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
      }
    },
    handleEvent(answer, event) {
      switch (event.event) {
        case 'session':
          if (event.content) this.sessionId = event.content
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
    scroll() { this.$nextTick(() => { if (this.$refs.feed) this.$refs.feed.scrollTop = this.$refs.feed.scrollHeight }) }
  }
}
</script>
