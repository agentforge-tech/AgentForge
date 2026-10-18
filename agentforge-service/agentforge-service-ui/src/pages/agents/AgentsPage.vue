<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>
    <aside class="af-sidebar">
      <div class="af-sidebar__head">
        <div class="af-brand">
          <span class="af-brand__logo"><img src="/logo.png" alt="AgentForge" /></span>
          <div><div class="af-brand__name">AgentForge</div><div class="af-brand__sub">AgentForge Service</div></div>
        </div>
      </div>
      <nav class="af-sidebar__scroll">
        <a class="af-nav-item af-nav-item--link" href="/chat">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 11.5a8.38 8.38 0 0 1-8.5 8.5 8.5 8.5 0 0 1-3.8-.9L3 21l1.9-5.7A8.5 8.5 0 0 1 12.5 3 8.38 8.38 0 0 1 21 11.5z" /></svg>
          <span class="af-nav-item__label">对话</span><span class="af-nav-item__hint">进入</span>
        </a>
        <div class="af-nav-group af-nav-group--agent">
          <button class="af-nav-group__head" type="button"><span>Agent 驾驶舱</span></button>
          <div class="af-nav-group__body">
            <button class="af-nav-item af-nav-item--active" type="button">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="8" r="3"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>
              <span class="af-nav-item__label">我的 Agent</span>
            </button>
          </div>
        </div>
        <div class="af-nav-group af-nav-group--resources">
          <button class="af-nav-group__head" type="button"><span>我的资源库</span></button>
          <div class="af-nav-group__body">
            <a class="af-nav-item af-nav-item--link" href="/">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z"/><path d="M12 11L4 6.5M12 11l8-4.5M12 11v9"/></svg>
              <span class="af-nav-item__label">模型列表</span>
            </a>
          </div>
        </div>
      </nav>
    </aside>

    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <button class="af-mobile-menu" type="button" @click="navOpen = true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h16"/></svg></button>
          <span>Agent 驾驶舱</span><span class="af-crumb__sep">/</span><span class="af-crumb__current">我的 Agent</span>
        </div>
        <button class="af-btn af-btn--primary af-btn--sm" type="button" @click="openCreate">+ 新建 Agent</button>
      </header>
      <section class="af-content"><div class="af-content__inner">
        <div class="af-page-head"><h1 class="af-page-head__title">我的 Agent</h1></div>
        <div class="af-toolbar">
          <div class="af-search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
            <input v-model="keyword" type="search" placeholder="搜索 Agent 名称或 AID..." />
          </div>
        </div>
        <div v-if="loading" class="af-loading"><span class="af-spinner"></span>正在加载 Agent 列表...</div>
        <div v-else-if="agents.length" class="af-grid">
          <article v-for="agent in agents" :key="agent.id" class="af-model-card af-agent-card" @click="openConfig(agent)">
            <div class="af-model-card__head">
              <span class="af-model-card__avatar">{{ initial(agent.name) }}</span>
              <div class="af-model-card__heading"><div class="af-model-card__title"><span class="af-model-card__name">{{ agent.name }}</span></div><div class="af-model-card__provider">ReActAgent</div></div>
            </div>
            <p class="af-model-card__desc">对话模型：{{ agent.modelName || '未配置' }}</p>
            <div class="af-model-card__tags"><span class="af-tag af-tag--brand">{{ agent.publish ? '已发布' : '未发布' }}</span></div>
            <div class="af-model-card__footer">
              <div class="af-model-card__meta">{{ formatTime(agent.modifyTime) }}</div>
              <div class="af-model-card__ops"><button class="af-btn af-btn--ghost af-btn--sm" @click.stop="openEdit(agent)">编辑</button><button class="af-btn af-btn--ghost af-btn--sm af-btn--danger" @click.stop="onDelete(agent)">删除</button></div>
            </div>
          </article>
        </div>
        <div v-else class="af-empty"><div class="af-empty__title">{{ keyword ? '没有找到匹配的 Agent' : '还没有 Agent' }}</div><button v-if="!keyword" class="af-btn af-btn--primary af-btn--sm" @click="openCreate">新建 Agent</button></div>
        <nav v-if="!loading && totalPage > 1" class="af-pagination">
          <button class="af-page-btn" :disabled="currentPage === 1" @click="changePage(currentPage - 1)">上一页</button>
          <button v-for="page in pageNumbers" :key="page" class="af-page-btn af-page-btn--number" :class="{ 'af-page-btn--active': page === currentPage }" @click="changePage(page)">{{ page }}</button>
          <button class="af-page-btn" :disabled="currentPage === totalPage" @click="changePage(currentPage + 1)">下一页</button>
        </nav>
      </div></section>
    </main>

    <div v-if="dialogVisible" class="af-modal">
      <div class="af-modal__backdrop" @click="closeDialog"></div>
      <div class="af-modal__panel" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">{{ form.id ? '编辑 Agent' : '新建 Agent' }}</h2><button class="af-modal__close" @click="closeDialog">×</button></div>
        <div class="af-modal__body">
          <div class="af-field"><label class="af-field__label">Agent 名称 <i>*</i></label><input v-model.trim="form.name" class="af-input" placeholder="例如：智能数据助手" /></div>
          <div class="af-field"><label class="af-field__label">对话模型 <i>*</i></label><select v-model.number="form.modelId" class="af-select"><option :value="null" disabled>请选择模型</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}（{{ model.modelname }}）</option></select><div v-if="!models.length" class="af-hint">暂无可用对话模型，请先在模型列表中新建。</div></div>
        </div>
        <div class="af-modal__footer"><span></span><div class="af-modal__footer-actions"><button class="af-btn af-btn--sm" @click="closeDialog">取消</button><button class="af-btn af-btn--primary af-btn--sm" :disabled="saving" @click="submit">{{ saving ? '创建中...' : '确定' }}</button></div></div>
      </div>
    </div>
    <div v-if="tip" class="af-toast af-toast--show" :class="{ 'af-toast--error': tipError }">{{ tip }}</div>
  </div>
</template>

<script>
import { fetchAgents, fetchChatModels, getAgent, upsertAgent, deleteAgent } from './api'

export default {
  name: 'AgentsPage',
  data() {
    return { navOpen: false, keyword: '', searchTimer: null, loading: false, saving: false, agents: [], models: [], currentPage: 1, pageSize: 12, totalPage: 0, dialogVisible: false, form: { id: null, name: '', modelId: null }, tip: '', tipError: false, tipTimer: null }
  },
  computed: {
    pageNumbers() { const pages = []; for (let i = 1; i <= this.totalPage; i += 1) pages.push(i); return pages.slice(Math.max(0, this.currentPage - 3), Math.max(5, this.currentPage + 2)) }
  },
  watch: {
    keyword() { clearTimeout(this.searchTimer); this.searchTimer = setTimeout(() => { this.currentPage = 1; this.loadAgents() }, 300) }
  },
  created() { this.loadAgents(); this.loadModels() },
  beforeDestroy() { clearTimeout(this.searchTimer); clearTimeout(this.tipTimer) },
  methods: {
    async loadAgents() { this.loading = true; try { const result = await fetchAgents({ page: this.currentPage, pageSize: this.pageSize, keyword: this.keyword.trim() }); this.agents = result.data || []; this.totalPage = result.totalPage || 0 } catch (e) { this.toast('加载 Agent 列表失败：' + e.message, true) } finally { this.loading = false } },
    async loadModels() { try { const result = await fetchChatModels(); this.models = result.data || [] } catch (e) { this.toast('加载模型列表失败：' + e.message, true) } },
    openCreate() { this.form = { id: null, name: '', modelId: null }; this.dialogVisible = true },
    openConfig(agent) { window.location.href = '/agents/config?id=' + agent.id },
    async openEdit(agent) { try { const detail = await getAgent(agent.id); this.form = { id: detail.id, name: detail.name, modelId: detail.modelId }; this.dialogVisible = true } catch (e) { this.toast('加载 Agent 详情失败：' + e.message, true) } },
    closeDialog() { if (!this.saving) this.dialogVisible = false },
    async submit() { if (!this.form.name || !this.form.modelId) return this.toast('请填写 Agent 名称并选择模型', true); this.saving = true; try { await upsertAgent(this.form); this.dialogVisible = false; this.toast(this.form.id ? 'Agent 已更新' : 'Agent 已创建'); this.loadAgents() } catch (e) { this.toast('保存失败：' + e.message, true) } finally { this.saving = false } },
    async onDelete(agent) { if (!window.confirm('确定删除 Agent「' + agent.name + '」吗？')) return; try { await deleteAgent(agent.id); this.toast('Agent 已删除'); this.loadAgents() } catch (e) { this.toast('删除失败：' + e.message, true) } },
    changePage(page) { if (page < 1 || page > this.totalPage || page === this.currentPage) return; this.currentPage = page; this.loadAgents() },
    initial(name) { return (name || 'A').trim().charAt(0).toUpperCase() },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' },
    toast(text, error = false) { this.tip = text; this.tipError = error; clearTimeout(this.tipTimer); this.tipTimer = setTimeout(() => { this.tip = '' }, 2600) }
  }
}
</script>
