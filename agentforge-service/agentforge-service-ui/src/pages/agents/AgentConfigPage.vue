<template>
  <div class="agent-config-page">
    <header class="agent-config-head">
      <a class="agent-config-back" href="/agents">← 返回我的 Agent</a>
      <div class="agent-config-title"><input v-model.trim="form.name" aria-label="Agent 名称" /><span>ReActAgent</span></div>
      <button class="af-btn af-btn--primary" :disabled="saving || loading" @click="save">{{ saving ? '保存中...' : '保存配置' }}</button>
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
            <div class="agent-config-summary"><span>Agent 类型</span><b>Standard / ReActAgent</b></div>
          </div>
          <div class="af-field"><label class="af-field__label">对话模型</label><select v-model.number="form.modelId" class="af-select"><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}（{{ model.modelname }}）</option></select></div>
          <div class="af-field"><label class="af-field__label">最大记忆消息数</label><input v-model.number="form.chatMemoryMaxMessages" class="af-input" type="number" min="1" max="10000" /><div class="af-hint">限制单个调试会话保留的历史消息数量。</div></div>
        </div>
      </section>

      <AgentDebugPanel :aid="detail.aid" />
    </main>
    <div v-if="tip" class="af-toast af-toast--show" :class="{ 'af-toast--error': tipError }">{{ tip }}</div>
  </div>
</template>

<script>
import { fetchChatModels, getAgent, upsertAgent } from './api'
import AgentDebugPanel from './AgentDebugPanel.vue'

export default {
  name: 'AgentConfigPage',
  components: { AgentDebugPanel },
  data() { return { loading: true, saving: false, detail: {}, models: [], form: { id: null, name: '', modelId: null, systemPrompt: '', chatMemoryMaxMessages: 100 }, tip: '', tipError: false, tipTimer: null } },
  async created() {
    const id = new URLSearchParams(window.location.search).get('id')
    if (!id) { window.location.href = '/agents'; return }
    try { const [detail, modelResult] = await Promise.all([getAgent(id), fetchChatModels()]); this.detail = detail; this.models = modelResult.data || []; this.form = { id: detail.id, name: detail.name, modelId: detail.modelId, systemPrompt: detail.systemPrompt || '', chatMemoryMaxMessages: detail.chatMemoryMaxMessages || 100 } } catch (e) { this.toast('加载 Agent 配置失败：' + e.message, true) } finally { this.loading = false }
  },
  beforeDestroy() { clearTimeout(this.tipTimer) },
  methods: {
    async save() { if (!this.form.name || !this.form.modelId || !this.form.systemPrompt) return this.toast('请完整填写 Agent 配置', true); this.saving = true; try { const detail = await upsertAgent(this.form); this.detail = detail; this.toast('Agent 配置已保存') } catch (e) { this.toast('保存失败：' + e.message, true) } finally { this.saving = false } },
    toast(text, error = false) { this.tip = text; this.tipError = error; clearTimeout(this.tipTimer); this.tipTimer = setTimeout(() => { this.tip = '' }, 2800) }
  }
}
</script>

<style lang="scss">
@use '../models/styles/tokens' as *;
.agent-config-page { min-height: 100vh; background: #f5f7fa; color: $color-text; }
.agent-config-head { height: 64px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 20px; padding: 0 24px; border-bottom: 1px solid $color-border; background: #fff; .af-btn { justify-self: end; } }
.agent-config-back { color: $color-text-secondary; text-decoration: none; font-size: 13px; }
.agent-config-title { display: flex; align-items: center; gap: 10px; input { width: 220px; border: 0; border-bottom: 1px solid transparent; text-align: center; font-size: 17px; font-weight: 700; outline: 0; &:focus { border-color: $color-brand; } } span { padding: 3px 8px; border-radius: 6px; background: $color-brand-softer; color: $color-brand-dark; font-size: 11px; } }
.agent-config-layout { height: calc(100vh - 64px); display: grid; grid-template-columns: minmax(300px, 1.05fr) minmax(300px, .9fr) minmax(340px, 1.05fr); gap: 12px; padding: 12px; }
.agent-config-panel { display: flex; min-width: 0; flex-direction: column; overflow: hidden; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: $shadow-xs; &__head { display: flex; justify-content: space-between; align-items: flex-start; padding: 16px; border-bottom: 1px solid $color-border; b { font-size: 14px; } p { margin: 5px 0 0; color: $color-text-muted; font-size: 11.5px; } } }
.agent-config-prompt textarea { flex: 1; width: 100%; resize: none; border: 0; outline: 0; padding: 18px; color: $color-text; font: 13px/1.75 $font-mono; }
.agent-config-fields { display: flex; flex-direction: column; gap: 20px; padding: 18px; }
.agent-config-overview { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: -4px 0 2px; }
.agent-config-summary { display: flex; flex-direction: column; gap: 6px; padding: 12px; border-radius: 8px; background: $color-sidebar; span { color: $color-text-muted; font-size: 11px; } code, b { overflow: hidden; color: $color-text-secondary; font-size: 12px; text-overflow: ellipsis; } }
.agent-debug-head { gap: 10px; }
.agent-debug-mode { max-width: 150px; height: 30px; border: 1px solid $color-border-strong; border-radius: 7px; background: #fff; color: $color-text-secondary; font-size: 11px; }
.agent-debug-feed { flex: 1; overflow-y: auto; padding: 16px; }
.agent-debug-empty { height: 100%; display: flex; align-items: center; justify-content: center; flex-direction: column; text-align: center; color: $color-text-muted; span { margin-bottom: 12px; color: $color-brand; font-size: 30px; } b { color: $color-text-secondary; font-size: 13px; } p { max-width: 240px; font-size: 11.5px; line-height: 1.6; } }
.agent-debug-message { margin-bottom: 16px; font-size: 13px; &--user { max-width: 82%; margin-left: auto; padding: 9px 12px; border-radius: 10px 10px 2px 10px; background: $color-brand; color: #fff; } &--assistant { padding-right: 12px; } }
.agent-debug-input { display: flex; gap: 8px; padding: 14px; border-top: 1px solid $color-border; textarea { flex: 1; height: 58px; resize: none; border: 1px solid $color-border-strong; border-radius: 8px; padding: 9px; outline: 0; } .af-btn { align-self: flex-end; } }
.af-agent-card { cursor: pointer; }
@media (max-width: 1100px) { .agent-config-layout { height: auto; grid-template-columns: 1fr 1fr; } .agent-debug { min-height: 520px; grid-column: 1 / -1; } }
@media (max-width: 720px) { .agent-config-head { grid-template-columns: 1fr auto; } .agent-config-title { display: none; } .agent-config-layout { grid-template-columns: 1fr; } .agent-config-panel { min-height: 480px; } .agent-debug { grid-column: auto; } }
</style>
