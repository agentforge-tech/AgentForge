<template>
  <div class="agent-config-page">
    <header class="agent-config-head">
      <a class="agent-config-back" href="/agents">← 返回我的 Agent</a>
      <div class="agent-config-title"><span class="agent-config-title__label">Agent：</span><strong>{{ form.name || '未命名 Agent' }}</strong></div>
      <div class="agent-config-head-actions">
        <a v-if="isPublished" class="af-btn agent-visit-btn" :href="publishedChatUrl" target="_blank" rel="noopener">访问发布页面 ↗</a>
        <button class="af-btn agent-publish-btn" :class="{ 'agent-publish-btn--active': isPublished }" :disabled="publishing || saving || loading" @click="togglePublish"><span v-if="isPublished && !publishing" class="agent-publish-live-dot" aria-hidden="true"></span>{{ publishing ? '处理中...' : (isPublished ? '停止发布' : '发布') }}</button>
        <button class="af-btn af-btn--primary" :disabled="saving || publishing || loading" @click="save">{{ saving ? '保存中...' : '保存配置' }}</button>
      </div>
    </header>

    <div v-if="loading" class="af-loading"><span class="af-spinner"></span>正在加载 Agent 配置...</div>
    <main v-else class="agent-config-layout">
      <section class="agent-config-panel agent-config-prompt">
        <div class="agent-config-panel__head"><div><b>Prompt 提示词</b><p>定义 Agent 的角色、目标和回答规则</p></div></div>
        <textarea v-model="form.systemPrompt" placeholder="请输入系统提示词..."></textarea>
      </section>

      <section class="agent-config-panel agent-config-details">
        <div class="agent-config-panel__head"><div><b>详细配置</b><p>配置 Agent 的模型与记忆策略</p></div></div>
        <div class="agent-config-fields">
          <div class="agent-config-overview">
            <div class="agent-config-summary"><span>Agent ID</span><code>{{ detail.aid }}</code></div>
            <div class="agent-config-summary"><span>Agent 类型</span><b>Standard ReAct Agent</b></div>
          </div>
          <div class="agent-config-group">
            <div class="agent-config-group__title">基本配置</div>
            <div class="af-field"><label class="af-field__label">Agent 名称</label><input v-model.trim="form.name" class="af-input" placeholder="请输入 Agent 名称" /></div>
            <div class="af-field"><label class="af-field__label">Agent 描述</label><textarea v-model.trim="form.description" class="af-input agent-description-input" rows="3" placeholder="简单介绍这个 Agent 的用途（选填）"></textarea></div>
          </div>
          <div class="af-field"><label class="af-field__label">对话模型</label><select v-model.number="form.modelId" class="af-select"><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}（{{ model.modelname }}）</option></select></div>
          <div class="af-field">
            <label class="af-field__label">记忆类型</label>
            <select v-model.number="form.chatMemoryType" class="af-select"><option :value="1">窗口会话记忆</option></select>
            <button v-if="form.chatMemoryType === 1" class="agent-memory-config-toggle" type="button" :aria-expanded="memoryConfigExpanded ? 'true' : 'false'" @click="memoryConfigExpanded = !memoryConfigExpanded">
              <span>窗口记忆参数</span><span class="agent-memory-config-toggle__icon" :class="{ 'agent-memory-config-toggle__icon--expanded': memoryConfigExpanded }">⌄</span>
            </button>
            <div v-if="form.chatMemoryType === 1 && memoryConfigExpanded" class="agent-memory-config">
              <label class="af-field__label">最大记忆消息数</label>
              <input v-model.number="form.chatMemoryMaxMessages" class="af-input" type="number" min="1" max="10000" />
              <div class="af-hint">限制单个调试会话保留的历史消息数量。</div>
            </div>
          </div>
        </div>
      </section>

      <AgentDebugPanel :aid="detail.aid" />
    </main>
    <div v-if="tip" class="af-toast af-toast--show" :class="{ 'af-toast--error': tipError }">{{ tip }}</div>
  </div>
</template>

<script>
import { fetchChatModels, getAgent, upsertAgent, updateAgentPublish } from './api'
import AgentDebugPanel from './AgentDebugPanel.vue'

export default {
  name: 'AgentConfigPage',
  components: { AgentDebugPanel },
  data() { return { loading: true, saving: false, publishing: false, memoryConfigExpanded: false, detail: {}, models: [], form: { id: null, name: '', description: '', modelId: null, systemPrompt: '', chatMemoryType: 1, chatMemoryMaxMessages: 1000 }, tip: '', tipError: false, tipTimer: null } },
  computed: {
    isPublished() { return Number(this.detail.publish) === 1 },
    publishedChatUrl() { return '/agent/published/' + encodeURIComponent(this.detail.aid || '') }
  },
  async created() {
    const id = new URLSearchParams(window.location.search).get('id')
    if (!id) { window.location.href = '/agents'; return }
    try { const [detail, modelResult] = await Promise.all([getAgent(id), fetchChatModels()]); this.detail = detail; this.models = modelResult.data || []; this.form = { id: detail.id, name: detail.name, description: detail.description || '', modelId: detail.modelId, systemPrompt: detail.systemPrompt || '', chatMemoryType: detail.chatMemoryType || 1, chatMemoryMaxMessages: detail.chatMemoryMaxMessages || 1000 } } catch (e) { this.toast('加载 Agent 配置失败：' + e.message, true) } finally { this.loading = false }
  },
  beforeDestroy() { clearTimeout(this.tipTimer) },
  methods: {
    async save() { if (!this.form.name || !this.form.modelId || !this.form.systemPrompt) return this.toast('请完整填写 Agent 配置', true); this.saving = true; try { const detail = await upsertAgent(this.form); this.detail = detail; this.toast('Agent 配置已保存') } catch (e) { this.toast('保存失败：' + e.message, true) } finally { this.saving = false } },
    async togglePublish() { const target = !this.isPublished; this.publishing = true; try { this.detail = await updateAgentPublish(this.detail.id, target); this.toast(target ? 'Agent 已发布' : 'Agent 已停止发布') } catch (e) { this.toast((target ? '发布' : '停止发布') + '失败：' + e.message, true) } finally { this.publishing = false } },
    toast(text, error = false) { this.tip = text; this.tipError = error; clearTimeout(this.tipTimer); this.tipTimer = setTimeout(() => { this.tip = '' }, 2800) }
  }
}
</script>

<style lang="scss">
@use '../models/styles/tokens' as *;
.agent-config-page { min-height: 100vh; background: #f5f7fa; color: $color-text; }
.agent-config-head { height: 64px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 20px; padding: 0 24px; border-bottom: 1px solid $color-border; background: #fff; }
.agent-config-head-actions { display: flex; justify-self: end; align-items: center; gap: 8px; }
.agent-visit-btn { border-color: #b9d2f5; background: #f4f8ff; color: #286bc5; text-decoration: none; &:hover { border-color: #78a8ea; background: #eaf3ff; } }
.agent-publish-btn { border-color: #8bb6f3; background: #eef5ff; color: #286bc5; &:hover { border-color: #5d98e8; background: #e3efff; } &--active { border-color: #efb0ac; background: #fff2f1; color: #c9423a; &:hover { border-color: #e17e77; background: #ffe8e6; } } }
.agent-publish-live-dot { position: relative; width: 7px; height: 7px; flex: 0 0 7px; margin-right: 1px; border-radius: 50%; background: #24b36b; box-shadow: 0 0 0 3px rgba(36, 179, 107, .14), 0 0 8px rgba(36, 179, 107, .65); animation: agent-publish-pulse 1.8s ease-in-out infinite; }
@keyframes agent-publish-pulse { 0%, 100% { opacity: 1; box-shadow: 0 0 0 3px rgba(36, 179, 107, .13), 0 0 7px rgba(36, 179, 107, .55); } 50% { opacity: .72; box-shadow: 0 0 0 5px rgba(36, 179, 107, .05), 0 0 11px rgba(36, 179, 107, .8); } }
.agent-config-back { color: $color-text-secondary; text-decoration: none; font-size: 13px; }
.agent-config-title { display: flex; align-items: center; gap: 2px; white-space: nowrap; &__label { color: $color-text-secondary; font-size: 15px; font-weight: 600; } strong { max-width: 260px; overflow: hidden; font-size: 17px; text-overflow: ellipsis; } }
.agent-config-layout { height: calc(100vh - 64px); display: grid; grid-template-columns: minmax(300px, 1.05fr) minmax(300px, .9fr) minmax(340px, 1.05fr); gap: 12px; padding: 12px; }
.agent-config-panel { display: flex; min-width: 0; flex-direction: column; overflow: hidden; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: $shadow-xs; &__head { display: flex; justify-content: space-between; align-items: flex-start; padding: 16px; border-bottom: 1px solid $color-border; b { font-size: 14px; } p { margin: 5px 0 0; color: $color-text-muted; font-size: 11.5px; } } }
.agent-config-prompt textarea { flex: 1; width: 100%; resize: none; border: 0; outline: 0; padding: 18px; color: $color-text; font: 13px/1.75 $font-mono; }
.agent-config-fields { display: flex; flex-direction: column; gap: 20px; padding: 18px; }
.agent-config-group { display: flex; flex-direction: column; gap: 14px; padding: 14px; border: 1px solid $color-border; border-radius: 9px; }
.agent-config-group__title { color: $color-text-secondary; font-size: 13px; font-weight: 700; }
.agent-description-input { min-height: 78px; resize: vertical; line-height: 1.5; }
.agent-memory-config-toggle { display: flex; width: 100%; align-items: center; justify-content: space-between; margin-top: 10px; border: 0; padding: 6px 2px; background: transparent; color: $color-text-secondary; cursor: pointer; font-size: 12px; }
.agent-memory-config-toggle__icon { display: inline-block; transition: transform .18s ease; &--expanded { transform: rotate(180deg); } }
.agent-memory-config { margin-top: 4px; padding: 14px; border-radius: 8px; background: $color-sidebar; }
.agent-memory-config .af-field__label { display: block; margin-bottom: 8px; }
.agent-config-overview { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: -4px 0 2px; }
.agent-config-summary { display: flex; flex-direction: column; gap: 6px; padding: 12px; border-radius: 8px; background: $color-sidebar; span { color: $color-text-muted; font-size: 11px; } code, b { overflow: hidden; color: $color-text-secondary; font-size: 12px; text-overflow: ellipsis; } }
.agent-debug-head { gap: 10px; }
.agent-debug-head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.agent-debug-mode { width: 172px; }
.agent-debug-clear-btn { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 28px; padding: 0; border: 1px solid #f2c8c5; border-radius: 5px; background: #fff5f4; color: #d94b43; cursor: pointer; box-shadow: 0 1px 2px rgba(217, 75, 67, .08); transition: all .15s; svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round; } &:hover { border-color: #e56b64; background: #feeceb; color: #c9362e; box-shadow: 0 2px 6px rgba(217, 75, 67, .18); transform: translateY(-1px); } &:active { transform: translateY(0); box-shadow: none; } }
.agent-debug-feed { flex: 1; overflow-y: auto; padding: 16px; }
.agent-debug-empty { height: 100%; display: flex; align-items: center; justify-content: center; flex-direction: column; text-align: center; color: $color-text-muted; span { margin-bottom: 12px; color: $color-brand; font-size: 30px; } b { color: $color-text-secondary; font-size: 13px; } p { max-width: 240px; font-size: 11.5px; line-height: 1.6; } }
.agent-debug-message-row { display: flex; width: 100%; margin-bottom: 20px; font-size: 13px; &--user { justify-content: flex-end; } &--assistant { justify-content: flex-start; gap: 12px; } }
.agent-debug-user-block { display: flex; max-width: 78%; flex-direction: column; align-items: flex-end; gap: 4px; }
.agent-debug-user-bubble { max-width: 100%; padding: 10px 14px; border-radius: 14px 14px 4px 14px; background: linear-gradient(135deg, #7cb0ff, #4b8ef7); box-shadow: 0 2px 10px rgba(96, 158, 255, .28); color: #fff; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.agent-debug-bot-avatar { display: flex; width: 32px; height: 32px; flex-shrink: 0; align-items: center; justify-content: center; overflow: hidden; border-radius: 9px; background: #f4f8ff; img { display: block; width: 100%; height: 100%; object-fit: cover; } }
.agent-debug-bot-body { max-width: calc(100% - 44px); min-width: 0; flex: 1; }
.agent-debug-message-actions { display: flex; align-items: center; gap: 6px; }
.agent-debug-mini-btn { padding: 4px 6px; border: 0; border-radius: 6px; background: transparent; color: #98a2b3; cursor: pointer; font-size: 14px; line-height: 1; transition: all .15s; &:hover { background: #f0f3f8; color: #3b6fb6; } &:disabled { cursor: not-allowed; opacity: .4; } }
.agent-debug-status-error { padding: 1px 6px; border-radius: 4px; background: #fff0f0; color: #d64545; font-size: 11px; }
.agent-debug-input { display: flex; align-items: center; gap: 10px; margin: 0 12px 12px; padding: 8px; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: 0 4px 16px rgba(31, 56, 88, .07); textarea { flex: 1; height: 54px; resize: none; border: 0; border-radius: 8px; padding: 10px 8px; outline: 0; background: transparent; color: $color-text; font: inherit; font-size: 13px; line-height: 1.55; } .af-btn { min-width: 68px; height: 36px; align-self: center; border-radius: 9px; } }
.af-agent-card { cursor: pointer; }
@media (max-width: 1100px) { .agent-config-layout { height: auto; grid-template-columns: 1fr 1fr; } .agent-debug { min-height: 520px; grid-column: 1 / -1; } }
@media (max-width: 720px) { .agent-config-head { grid-template-columns: 1fr auto; } .agent-config-title { display: none; } .agent-config-layout { grid-template-columns: 1fr; } .agent-config-panel { min-height: 480px; } .agent-debug { grid-column: auto; } }
</style>
