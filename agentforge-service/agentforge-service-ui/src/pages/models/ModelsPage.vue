<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>

    <AppSidebar />

    <!-- ------------------------------ 主工作区 ------------------------------ -->
    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <el-button class="af-mobile-menu" type="text" icon="el-icon-s-fold" aria-label="打开导航菜单" @click="navOpen = true" />
          <span>Agent组件</span>
          <span class="af-crumb__sep">/</span>
          <span class="af-crumb__current">模型列表</span>
        </div>
        <div class="af-topbar__actions">
          <el-button icon="el-icon-refresh" :disabled="loading" @click="loadModels">刷新</el-button>
          <el-button type="primary" icon="el-icon-plus" @click="openCreate">新建模型</el-button>
        </div>
      </header>

      <section class="af-content">
        <div class="af-content__inner">
          <!-- 工具栏 -->
          <div class="af-list-toolbar">
            <el-input
              v-model="keyword"
              class="af-list-toolbar__search"
              clearable
              prefix-icon="el-icon-search"
              aria-label="搜索模型"
              placeholder="搜索模型名称、协议或模型名..."
            />
            <el-select v-model="filter" aria-label="模型类型筛选" @change="changeFilter">
              <el-option v-for="f in filters" :key="f.key" :label="f.label" :value="f.key" />
            </el-select>
          </div>

          <!-- 加载中 -->
          <div v-if="loading" class="af-loading">
            <span class="af-spinner"></span>
            正在加载模型列表...
          </div>

          <!-- 模型卡片网格 -->
          <div v-else-if="models.length" class="af-grid">
            <article v-for="m in models" :key="m.id" class="af-model-card">
              <div class="af-model-card__head">
                <span
                  class="af-model-card__avatar"
                  :class="{ 'af-model-card__avatar--logo': modelLogo(m) }"
                  :style="{ '--provider': providerColor(m.provider) }"
                >
                  <img v-if="modelLogo(m)" :src="modelLogo(m)" :alt="modelBrand(m) + ' logo'" />
                  <template v-else>{{ initial(m.name) }}</template>
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

          <!-- 分页 -->
          <div v-if="!loading && totalCount > 0" class="af-pagination">
            <div class="af-pagination__summary">共 {{ totalCount }} 个模型，第 {{ currentPage }} / {{ totalPage }} 页</div>
            <el-pagination
              background
              layout="sizes, prev, pager, next, jumper"
              :page-sizes="[12, 24, 48]"
              :page-size="pageSize"
              :current-page="currentPage"
              :total="totalCount"
              @size-change="changePageSize"
              @current-change="changePage"
            />
          </div>
        </div>
      </section>
    </main>

    <!-- ------------------------------ 新建 / 编辑弹窗 ------------------------------ -->
    <el-dialog
      :visible.sync="dialogVisible"
      :title="form.id ? '编辑模型' : '新建模型'"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <el-form :model="form" label-width="96px" @submit.native.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：我的 GPT-4o" />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="模型协议" required>
              <el-select v-model.number="form.provider" placeholder="请选择协议" style="width: 100%">
                <el-option v-for="p in options.providers" :key="p.value" :label="p.label" :value="p.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型类别" required>
              <el-select v-model.number="form.modelType" placeholder="请选择类别" style="width: 100%">
                <el-option v-for="t in options.modelTypes" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="连接地址" required>
          <el-input v-model="form.url" placeholder="例如：https://api.openai.com/v1" />
        </el-form-item>

        <el-form-item label="模型名称" required>
          <el-input v-model="form.modelname" placeholder="例如：gpt-4o / deepseek-chat" />
        </el-form-item>

        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="form.hasApiKey ? '••••••••••••••••' : 'sk-...'"
          />
          <div class="af-form-tip">
            {{ form.hasApiKey ? '密钥已加密保存且不会回显；留空保留原密钥，输入新值则替换。' : '选填，密钥仅用于后端调用模型。' }}
          </div>
        </el-form-item>
      </el-form>

      <span slot="footer" class="af-dialog-footer">
        <el-button
          :disabled="saving || Boolean(testingForm)"
          :loading="testingForm === 'connectivity'"
          @click="onTestFormConnectivity"
        >测试连通性</el-button>
        <el-button
          :disabled="saving || Boolean(testingForm)"
          :loading="testingForm === 'functionCall'"
          @click="onTestFormFunctionCall"
        >测试 Function Call</el-button>
        <span class="af-dialog-footer__spacer"></span>
        <el-button :disabled="saving" @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import AppSidebar from '../../components/AppSidebar.vue'
import {
  fetchOptions,
  fetchModels,
  getModel,
  upsertModel,
  deleteModel,
  testConnectivity,
  testFunctionCall
} from '../../api/model'

const PROVIDER_COLORS = {
  1: '#10a37f',
  2: '#d97757'
}

export default {
  name: 'ModelsPage',
  components: { AppSidebar },
  data() {
    return {
      navOpen: false,
      keyword: '',
      filter: 'all',
      searchTimer: null,
      loading: false,
      saving: false,
      testing: null,
      editingId: null,
      testingForm: null,
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
      const chips = [{ key: 'all', label: '全部类型' }]
      this.options.modelTypes.forEach((t) => {
        chips.push({ key: 'type-' + t.value, label: t.label })
      })
      return chips
    },
    hasFilters() {
      return Boolean(this.keyword.trim()) || this.filter !== 'all'
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
      if (this.options.providers.length) this.form.provider = this.options.providers[0].value
      if (this.options.modelTypes.length) this.form.modelType = this.options.modelTypes[0].value
      this.dialogVisible = true
    },
    async openEdit(model) {
      if (this.editingId !== null) return
      this.editingId = model.id
      try {
        const detail = await getModel(model.id)
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
    async onDelete(model) {
      try {
        await this.$confirm('删除后该模型配置将不可恢复，是否继续？', '删除模型「' + model.name + '」', {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消'
        })
      } catch (_e) {
        return
      }
      try {
        await deleteModel(model.id)
        this.toast('已删除「' + model.name + '」')
        this.loadModels()
      } catch (e) {
        this.toast('删除失败：' + e.message, true)
      }
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
    formTestPayload() {
      const payload = {
        modelId: this.form.id,
        provider: this.form.provider,
        modelType: this.form.modelType,
        testType: this.form.modelType,
        modelname: this.form.modelname,
        url: this.form.url
      }
      if (this.form.apiKey) payload.apiKey = this.form.apiKey
      return payload
    },
    async onTestFormConnectivity() {
      if (!this.form.url || !this.form.modelname) {
        this.toast('请先填写连接地址和模型名称后再进行测试', true)
        return
      }
      this.testingForm = 'connectivity'
      try {
        const result = await testConnectivity(this.formTestPayload())
        const success = Boolean(result && result.success)
        const message = (result && result.message) || '测试已完成，但服务端未返回详细信息'
        this.toast((success ? '连通性测试通过：' : '连通性测试未通过：') + message, !success)
      } catch (e) {
        this.toast('连通性测试未通过：' + (e.message || '请求失败，请检查模型配置后重试'), true)
      } finally {
        this.testingForm = null
      }
    },
    async onTestFormFunctionCall() {
      if (!this.form.url || !this.form.modelname) {
        this.toast('请先填写连接地址和模型名称后再进行测试', true)
        return
      }
      this.testingForm = 'functionCall'
      try {
        const result = await testFunctionCall(this.formTestPayload())
        const success = Boolean(result && result.success)
        const message = (result && result.message) || '测试已完成，但服务端未返回详细信息'
        this.toast((success ? 'Function Call 测试通过：' : 'Function Call 测试未通过：') + message, !success)
      } catch (e) {
        this.toast('Function Call 测试未通过：' + (e.message || '请求失败，请检查模型配置后重试'), true)
      } finally {
        this.testingForm = null
      }
    },
    changeFilter(filter) {
      if (this.filter !== filter) this.filter = filter
      this.currentPage = 1
      this.loadModels()
    },
    changePage(page) {
      if (page < 1 || page > this.totalPage || page === this.currentPage) return
      this.currentPage = page
      this.loadModels()
    },
    changePageSize(size) {
      this.pageSize = size
      this.currentPage = 1
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
    modelBrand(model) {
      const identity = [model && model.name, model && model.modelname]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      if (identity.includes('deepseek')) return 'DeepSeek'
      if (/\bgpt(?:[-_. ]|\d|$)/.test(identity)) return 'OpenAI'
      return ''
    },
    modelLogo(model) {
      const logos = {
        DeepSeek: '/model-logos/deepseek.svg',
        OpenAI: '/model-logos/openai.svg'
      }
      return logos[this.modelBrand(model)] || ''
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
      this.$message({ message: text, type: isError ? 'error' : 'success', duration: 2400 })
    }
  }
}
</script>

<style lang="scss" scoped>
.af-dialog-footer {
  display: flex;
  align-items: center;
  gap: 8px;

  &__spacer {
    flex: 1;
  }
}
</style>
