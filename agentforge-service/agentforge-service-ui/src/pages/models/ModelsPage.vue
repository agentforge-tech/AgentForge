<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>

    <!-- ------------------------------ 左侧导航 ------------------------------ -->
    <aside class="af-sidebar">
      <div class="af-sidebar__head">
        <div class="af-brand">
          <span class="af-brand__logo"><img src="/logo.png" alt="AgentForge" /></span>
          <div>
            <div class="af-brand__name">AgentForge</div>
            <div class="af-brand__sub">AgentForge Service</div>
          </div>
        </div>
      </div>

      <nav class="af-sidebar__scroll">
        <a class="af-nav-item af-nav-item--link" href="/chat">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 11.5a8.38 8.38 0 0 1-8.5 8.5 8.5 8.5 0 0 1-3.8-.9L3 21l1.9-5.7A8.5 8.5 0 0 1 12.5 3 8.38 8.38 0 0 1 21 11.5z" />
          </svg>
          <span class="af-nav-item__label">对话</span>
          <span class="af-nav-item__hint">进入</span>
        </a>

        <div class="af-nav-group af-nav-group--agent">
          <button class="af-nav-group__head" @click="agentGroupOpen = !agentGroupOpen" type="button" :aria-expanded="agentGroupOpen">
            <span>Agent 驾驶舱</span>
            <svg
              class="af-nav-group__chevron"
              :class="{ 'af-nav-group__chevron--collapsed': !agentGroupOpen }"
              viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round"
            >
              <path d="M6 9l6 6 6-6" />
            </svg>
          </button>

          <transition name="af-fade">
            <div v-show="agentGroupOpen" class="af-nav-group__body">
              <a class="af-nav-item af-nav-item--link" href="/agents">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="8" r="3" />
                  <path d="M5 20a7 7 0 0 1 14 0M4 5l2 1M20 5l-2 1M12 2v2" />
                </svg>
                <span class="af-nav-item__label">我的 Agent</span>
              </a>
            </div>
          </transition>
        </div>

        <div class="af-nav-group af-nav-group--resources">
          <button class="af-nav-group__head" @click="groupOpen = !groupOpen" type="button" :aria-expanded="groupOpen">
            <span>我的资源库</span>
            <svg
              class="af-nav-group__chevron"
              :class="{ 'af-nav-group__chevron--collapsed': !groupOpen }"
              viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round"
            >
              <path d="M6 9l6 6 6-6" />
            </svg>
          </button>

          <transition name="af-fade">
            <div v-show="groupOpen" class="af-nav-group__body">
              <button class="af-nav-item af-nav-item--active" type="button">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z" />
                  <path d="M12 11L4 6.5M12 11l8-4.5M12 11v9" />
                </svg>
                <span class="af-nav-item__label">模型列表</span>
              </button>
            </div>
          </transition>
        </div>
      </nav>

      <div class="af-sidebar__footer">
        <div class="af-user">
          <span class="af-user__avatar">A</span>
          <div class="af-user__meta">
            <div class="af-user__name">Agent 开发者</div>
            <div class="af-user__plan">专业版 · Pro</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- ------------------------------ 主工作区 ------------------------------ -->
    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <button class="af-mobile-menu" type="button" aria-label="打开导航菜单" @click="navOpen = true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <span>我的资源库</span>
          <span class="af-crumb__sep">/</span>
          <span class="af-crumb__current">模型列表</span>
        </div>
        <div class="af-topbar__actions">
          <button class="af-btn af-btn--icon af-btn--ghost" title="刷新" :disabled="loading" @click="loadModels">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 12a9 9 0 1 1-2.64-6.36" />
              <path d="M21 3v6h-6" />
            </svg>
          </button>
          <button class="af-btn af-btn--primary af-btn--sm" @click="openCreate">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新建模型
          </button>
        </div>
      </header>

      <section class="af-content">
        <div class="af-content__inner">
          <div class="af-page-head">
            <div class="af-page-head__text">
              <h1 class="af-page-head__title">模型列表</h1>
            </div>
          </div>

          <!-- 工具栏 -->
          <div class="af-toolbar">
            <div class="af-search">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="7" />
                <path d="M21 21l-4.3-4.3" />
              </svg>
              <input
                v-model="keyword"
                type="search"
                aria-label="搜索模型"
                placeholder="搜索模型名称、协议或模型名..."
              />
              <button v-if="keyword" class="af-search__clear" type="button" aria-label="清除搜索" @click="clearKeyword">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                  <path d="M18 6L6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
            <div class="af-chips" role="group" aria-label="模型筛选">
              <button
                v-for="f in filters"
                :key="f.key"
                type="button"
                class="af-chip"
                :class="{ 'af-chip--active': filter === f.key }"
                :aria-pressed="filter === f.key"
                @click="changeFilter(f.key)"
              >
                {{ f.label }}
              </button>
            </div>
          </div>

          <!-- 加载中 -->
          <div v-if="loading" class="af-loading">
            <span class="af-spinner"></span>
            正在加载模型列表...
          </div>

          <!-- 模型网格 -->
          <div v-else-if="models.length" class="af-grid">
            <article v-for="m in models" :key="m.id" class="af-model-card">
              <div class="af-model-card__head">
                <span class="af-model-card__avatar" :style="{ '--provider': providerColor(m.provider) }">
                  {{ initial(m.name) }}
                </span>
                <div class="af-model-card__heading">
                  <div class="af-model-card__title">
                    <span class="af-model-card__name">{{ m.name }}</span>
                  </div>
                  <div class="af-model-card__provider">{{ m.providerName || '未知协议' }}</div>
                </div>
              </div>

              <p class="af-model-card__desc">
                {{ m.modelname }}<template v-if="m.url"> · {{ m.url }}</template>
              </p>

              <div class="af-model-card__tags">
                <span class="af-tag af-tag--brand">{{ m.modelTypeName || modelTypeLabel(m.modelType) }}</span>
                <span class="af-tag">{{ createTypeLabel(m.createType) }}</span>
              </div>

              <div class="af-model-card__footer">
                <div class="af-model-card__meta">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="9" />
                    <path d="M12 7v5l3 2" />
                  </svg>
                  <span>{{ formatTime(m.modifyTime || m.createTime) }}</span>
                </div>
                <div class="af-model-card__ops">
                  <button class="af-btn af-btn--ghost af-btn--sm" :disabled="testing === m.id" @click="onTestConn(m)">
                    {{ testing === m.id ? '测试中' : '测试连接' }}
                  </button>
                  <button class="af-btn af-btn--ghost af-btn--sm" :disabled="editingId === m.id" @click="openEdit(m)">
                    {{ editingId === m.id ? '加载中' : '编辑' }}
                  </button>
                  <button class="af-btn af-btn--ghost af-btn--sm af-btn--danger" @click="onDelete(m)">删除</button>
                </div>
              </div>
            </article>
          </div>

          <!-- 空状态 -->
          <div v-else class="af-empty">
            <span class="af-empty__icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z" />
                <path d="M12 11L4 6.5M12 11l8-4.5M12 11v9" />
              </svg>
            </span>
            <div class="af-empty__title">{{ hasFilters ? '没有找到匹配的模型' : '还没有模型，先新建一个吧' }}</div>
            <div>{{ hasFilters ? '试试更换关键词，或切换上方的筛选条件。' : '接入 OpenAI / Anthropic 兼容模型后即可开始对话。' }}</div>
            <button v-if="hasFilters" class="af-btn af-btn--sm" type="button" @click="resetFilters">清除筛选条件</button>
            <button v-else class="af-btn af-btn--primary af-btn--sm" type="button" @click="openCreate">新建模型</button>
          </div>

          <nav v-if="!loading && totalPage > 1" class="af-pagination" aria-label="模型列表分页">
            <button class="af-page-btn" type="button" :disabled="currentPage === 1" @click="changePage(currentPage - 1)">
              上一页
            </button>
            <button
              v-for="page in pageNumbers"
              :key="page"
              class="af-page-btn af-page-btn--number"
              :class="{ 'af-page-btn--active': page === currentPage }"
              type="button"
              :aria-current="page === currentPage ? 'page' : null"
              @click="changePage(page)"
            >
              {{ page }}
            </button>
            <button class="af-page-btn" type="button" :disabled="currentPage === totalPage" @click="changePage(currentPage + 1)">
              下一页
            </button>
          </nav>
        </div>
      </section>
    </main>

    <!-- ------------------------------ 新建 / 编辑弹窗 ------------------------------ -->
    <transition name="af-fade">
      <div v-if="dialogVisible" class="af-modal">
        <div class="af-modal__backdrop" @click="closeDialog"></div>
        <div class="af-modal__panel" role="dialog" aria-modal="true">
          <div class="af-modal__head">
            <h2 class="af-modal__title">{{ form.id ? '编辑模型' : '新建模型' }}</h2>
            <button class="af-modal__close" type="button" aria-label="关闭" @click="closeDialog">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <path d="M18 6L6 18M6 6l12 12" />
              </svg>
            </button>
          </div>

          <div class="af-modal__body">
            <div class="af-field">
              <label class="af-field__label">名称 <i>*</i></label>
              <input v-model="form.name" class="af-input" type="text" placeholder="例如：我的 GPT-4o" />
            </div>

            <div class="af-field-row">
              <div class="af-field">
                <label class="af-field__label">模型协议 <i>*</i></label>
                <select v-model.number="form.provider" class="af-select">
                  <option v-for="p in options.providers" :key="p.value" :value="p.value">{{ p.label }}</option>
                </select>
              </div>
              <div class="af-field">
                <label class="af-field__label">模型类别 <i>*</i></label>
                <select v-model.number="form.modelType" class="af-select">
                  <option v-for="t in options.modelTypes" :key="t.value" :value="t.value">{{ t.label }}</option>
                </select>
              </div>
            </div>

            <div class="af-field">
              <label class="af-field__label">连接地址 <i>*</i></label>
              <input v-model="form.url" class="af-input" type="text" placeholder="例如：https://api.openai.com/v1" />
            </div>

            <div class="af-field">
              <label class="af-field__label">模型名称 <i>*</i></label>
              <input v-model="form.modelname" class="af-input" type="text" placeholder="例如：gpt-4o / deepseek-chat" />
            </div>

            <div class="af-field">
              <label class="af-field__label">API Key</label>
              <input
                v-model="form.apiKey"
                class="af-input"
                type="password"
                autocomplete="new-password"
                :placeholder="form.hasApiKey ? '••••••••••••••••' : 'sk-...'"
              />
              <div class="af-hint">
                {{ form.hasApiKey ? '密钥已加密保存且不会回显；留空保留原密钥，输入新值则替换。' : '选填，密钥仅用于后端调用模型。' }}
              </div>
            </div>

            <transition name="af-fade">
              <div
                v-if="formTestResult"
                class="af-test-result"
                :class="formTestResult.success ? 'af-test-result--success' : 'af-test-result--error'"
                role="status"
              >
                <span class="af-test-result__icon">
                  <svg v-if="formTestResult.success" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M20 6L9 17l-5-5" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
                    <path d="M18 6L6 18M6 6l12 12" />
                  </svg>
                </span>
                <div class="af-test-result__content">
                  <div class="af-test-result__title">
                    {{ formTestResult.success ? 'Function Call 测试通过' : 'Function Call 测试未通过' }}
                  </div>
                  <div class="af-test-result__message">{{ formTestResult.message }}</div>
                </div>
                <button class="af-test-result__close" type="button" aria-label="关闭测试结果" @click="formTestResult = null">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                    <path d="M18 6L6 18M6 6l12 12" />
                  </svg>
                </button>
              </div>
            </transition>
          </div>

          <div class="af-modal__footer">
            <button
              class="af-btn af-btn--sm"
              type="button"
              :disabled="saving || testingForm"
              @click="onTestFormFunctionCall"
            >
              {{ testingForm ? '测试中...' : '测试 Function Call' }}
            </button>
            <div class="af-modal__footer-actions">
              <button class="af-btn af-btn--sm" type="button" @click="closeDialog">取消</button>
              <button class="af-btn af-btn--primary af-btn--sm" type="button" :disabled="saving || testingForm" @click="submitForm">
                {{ saving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </transition>

    <transition name="af-fade">
      <div v-if="tip" class="af-toast af-toast--show" :class="{ 'af-toast--error': tipError }">{{ tip }}</div>
    </transition>
  </div>
</template>

<script>
import {
  fetchOptions,
  fetchModels,
  getModel,
  upsertModel,
  deleteModel,
  testConnectivity,
  testFunctionCall
} from './api'

const PROVIDER_COLORS = {
  1: '#10a37f',
  2: '#d97757'
}

export default {
  name: 'ModelsPage',
  data() {
    return {
      agentGroupOpen: true,
      groupOpen: true,
      navOpen: false,
      keyword: '',
      filter: 'all',
      tip: '',
      tipError: false,
      tipTimer: null,
      searchTimer: null,
      loading: false,
      saving: false,
      testing: null,
      editingId: null,
      testingForm: false,
      formTestResult: null,
      options: { providers: [], modelTypes: [] },
      models: [],
      currentPage: 1,
      pageSize: 12,
      totalCount: 0,
      totalPage: 0,
      dialogVisible: false,
      form: { id: null, name: '', provider: 1, modelType: 0, modelname: '', url: '', apiKey: '', hasApiKey: false }
    }
  },
  computed: {
    filters() {
      const chips = [{ key: 'all', label: '全部' }]
      this.options.modelTypes.forEach((t) => {
        chips.push({ key: 'type-' + t.value, label: t.label })
      })
      return chips
    },
    hasFilters() {
      return Boolean(this.keyword.trim()) || this.filter !== 'all'
    },
    pageNumbers() {
      const start = Math.max(1, Math.min(this.currentPage - 2, this.totalPage - 4))
      const end = Math.min(this.totalPage, start + 4)
      const pages = []
      for (let page = start; page <= end; page += 1) pages.push(page)
      return pages
    }
  },
  watch: {
    keyword() {
      if (this.searchTimer) clearTimeout(this.searchTimer)
      this.searchTimer = setTimeout(() => {
        this.currentPage = 1
        this.loadModels()
      }, 300)
    }
  },
  created() {
    this.loadOptions()
    this.loadModels()
  },
  beforeDestroy() {
    if (this.tipTimer) clearTimeout(this.tipTimer)
    if (this.searchTimer) clearTimeout(this.searchTimer)
  },
  methods: {
    emptyForm() {
      return { id: null, name: '', provider: 1, modelType: 0, modelname: '', url: '', apiKey: '', hasApiKey: false }
    },
    async loadOptions() {
      try {
        const data = await fetchOptions()
        if (data) this.options = data
      } catch (e) {
        this.toast('加载枚举选项失败：' + e.message, true)
      }
    },
    async loadModels() {
      this.loading = true
      try {
        const query = {
          page: this.currentPage,
          pageSize: this.pageSize,
          keyword: this.keyword.trim()
        }
        if (this.filter.startsWith('type-')) query.modelType = Number(this.filter.slice(5))
        const result = await fetchModels(query)
        this.models = result && Array.isArray(result.data) ? result.data : []
        this.totalCount = result ? Number(result.totalCount) || 0 : 0
        this.totalPage = result ? Number(result.totalPage) || 0 : 0
        if (this.totalPage > 0 && this.currentPage > this.totalPage) {
          this.currentPage = this.totalPage
          return this.loadModels()
        }
      } catch (e) {
        this.models = []
        this.toast('加载模型列表失败：' + e.message, true)
      } finally {
        this.loading = false
      }
    },
    openCreate() {
      this.form = this.emptyForm()
      this.formTestResult = null
      if (this.options.providers.length) this.form.provider = this.options.providers[0].value
      if (this.options.modelTypes.length) this.form.modelType = this.options.modelTypes[0].value
      this.dialogVisible = true
    },
    async openEdit(model) {
      if (this.editingId !== null) return
      this.editingId = model.id
      try {
        const detail = await getModel(model.id)
        this.formTestResult = null
        this.form = {
          id: detail.id,
          name: detail.name,
          provider: detail.provider,
          modelType: detail.modelType,
          modelname: detail.modelname,
          url: detail.url || '',
          apiKey: detail.apiKey || '',
          hasApiKey: Boolean(detail.hasApiKey)
        }
        this.dialogVisible = true
      } catch (e) {
        this.toast('加载模型详情失败：' + e.message, true)
      } finally {
        this.editingId = null
      }
    },
    closeDialog() {
      if (this.saving) return
      this.dialogVisible = false
    },
    async submitForm() {
      if (!this.form.name || !this.form.url || !this.form.modelname) {
        this.toast('名称、连接地址与模型名称为必填项', true)
        return
      }
      this.saving = true
      try {
        const payload = {
          id: this.form.id,
          name: this.form.name,
          provider: this.form.provider,
          modelType: this.form.modelType,
          modelname: this.form.modelname,
          url: this.form.url || null
        }
        if (this.form.apiKey) payload.apiKey = this.form.apiKey
        await upsertModel(payload)
        this.dialogVisible = false
        this.toast(this.form.id ? '模型已更新' : '模型已创建')
        this.loadModels()
      } catch (e) {
        this.toast('保存失败：' + e.message, true)
      } finally {
        this.saving = false
      }
    },
    onDelete(model) {
      if (!window.confirm('确定删除模型「' + model.name + '」吗？')) return
      deleteModel(model.id)
        .then(() => {
          this.toast('已删除「' + model.name + '」')
          this.loadModels()
        })
        .catch((e) => this.toast('删除失败：' + e.message, true))
    },
    testPayload(model) {
      return {
        modelId: model.id,
        provider: model.provider,
        modelType: model.modelType,
        testType: model.modelType
      }
    },
    async onTestConn(model) {
      this.testing = model.id
      try {
        const result = await testConnectivity(this.testPayload(model))
        this.toast((result && result.message) || '测试完成', !(result && result.success))
      } catch (e) {
        this.toast('连通性测试失败：' + e.message, true)
      } finally {
        this.testing = null
      }
    },
    async onTestFormFunctionCall() {
      if (!this.form.url || !this.form.modelname) {
        this.formTestResult = { success: false, message: '请先填写连接地址和模型名称后再进行测试。' }
        return
      }
      this.testingForm = true
      this.formTestResult = null
      try {
        const payload = {
          modelId: this.form.id,
          provider: this.form.provider,
          modelType: this.form.modelType,
          testType: this.form.modelType,
          modelname: this.form.modelname,
          url: this.form.url
        }
        if (this.form.apiKey) payload.apiKey = this.form.apiKey
        const result = await testFunctionCall(payload)
        this.formTestResult = {
          success: Boolean(result && result.success),
          message: (result && result.message) || '测试已完成，但服务端未返回详细信息。'
        }
      } catch (e) {
        this.formTestResult = { success: false, message: e.message || '请求失败，请检查模型配置后重试。' }
      } finally {
        this.testingForm = false
      }
    },
    clearKeyword() {
      this.keyword = ''
    },
    changeFilter(filter) {
      if (this.filter === filter) return
      this.filter = filter
      this.currentPage = 1
      this.loadModels()
    },
    changePage(page) {
      if (page < 1 || page > this.totalPage || page === this.currentPage) return
      this.currentPage = page
      this.loadModels()
    },
    resetFilters() {
      this.keyword = ''
      this.filter = 'all'
      this.currentPage = 1
      this.loadModels()
    },
    initial(name) {
      return (name || '?').trim().charAt(0).toUpperCase()
    },
    providerColor(provider) {
      return PROVIDER_COLORS[provider] || '#4b8ef7'
    },
    modelTypeLabel(modelType) {
      const found = this.options.modelTypes.find((t) => t.value === modelType)
      return found ? found.label : '未知'
    },
    createTypeLabel(createType) {
      return createType === 0 ? '系统内置' : '用户创建'
    },
    formatTime(value) {
      if (!value) return '—'
      const date = new Date(value)
      if (isNaN(date.getTime())) return '—'
      return date.toLocaleString('zh-CN', { hour12: false })
    },
    toast(text, isError = false) {
      this.tip = text
      this.tipError = isError
      if (this.tipTimer) clearTimeout(this.tipTimer)
      this.tipTimer = setTimeout(() => {
        this.tip = ''
      }, 2400)
    }
  }
}
</script>
