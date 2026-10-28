<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>
    <AppSidebar active="agents" />

    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <el-button class="af-mobile-menu" type="text" icon="el-icon-s-fold" aria-label="打开导航菜单" @click="navOpen = true" />
          <span>Agent 驾驶舱</span>
          <span class="af-crumb__sep">/</span>
          <span class="af-crumb__current">Agent 列表</span>
        </div>
        <div class="af-topbar__actions">
          <el-button icon="el-icon-refresh" :disabled="loading" @click="loadAgents">刷新</el-button>
          <el-button type="primary" icon="el-icon-plus" @click="openCreate">新建 Agent</el-button>
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
              aria-label="搜索 Agent"
              placeholder="搜索 Agent 名称或 AID..."
            />
          </div>

          <!-- 加载中 -->
          <div v-if="loading" class="af-loading"><span class="af-spinner"></span>正在加载 Agent 列表...</div>

          <!-- Agent 卡片网格 -->
          <div v-else-if="agents.length" class="af-grid">
            <article
              v-for="agent in agents"
              :key="agent.id"
              class="af-model-card af-agent-card"
              :class="{ 'af-agent-card--opening': openingId === agent.id }"
              :aria-busy="openingId === agent.id ? 'true' : 'false'"
              @click="openConfig(agent)"
              @mouseenter="schedulePrefetch(agent)"
              @focusin="schedulePrefetch(agent)"
            >
              <div class="af-model-card__head">
                <span class="af-model-card__avatar">{{ initial(agent.name) }}</span>
                <div class="af-model-card__heading">
                  <div class="af-model-card__title">
                    <span class="af-model-card__name">{{ agent.name }}</span>
                  </div>
                  <div class="af-model-card__provider">ReActAgent</div>
                </div>
              </div>
              <p class="af-model-card__desc">{{ agent.description || '暂无描述' }}</p>
              <div class="af-model-card__tags">
                <span class="af-tag af-tag--brand">{{ agent.publish ? '已发布' : '未发布' }}</span>
              </div>
              <div class="af-model-card__footer">
                <div class="af-model-card__meta">{{ formatTime(agent.modifyTime) }}</div>
                <div class="af-model-card__ops">
                  <button class="af-btn af-btn--ghost af-btn--sm" @click.stop="openEdit(agent)">编辑</button>
                  <button class="af-btn af-btn--ghost af-btn--sm af-btn--danger" @click.stop="onDelete(agent)">删除</button>
                </div>
              </div>

              <!-- 点击进入编辑页时的即时反馈 -->
              <span v-if="openingId === agent.id" class="af-agent-card__mask">
                <i class="el-icon-loading"></i>
                <span>正在打开配置…</span>
              </span>
            </article>
          </div>

          <!-- 空状态 -->
          <div v-else class="af-empty">
            <div class="af-empty__title">{{ keyword ? '没有找到匹配的 Agent' : '还没有 Agent' }}</div>
            <button v-if="!keyword" class="af-btn af-btn--primary af-btn--sm" @click="openCreate">新建 Agent</button>
          </div>

          <!-- 分页 -->
          <div v-if="!loading && totalCount > 0" class="af-pagination">
            <div class="af-pagination__summary">共 {{ totalCount }} 个 Agent，第 {{ currentPage }} / {{ totalPage }} 页</div>
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

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog
      :visible.sync="dialogVisible"
      :title="form.id ? '编辑 Agent' : '新建 Agent'"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <el-form :model="form" label-width="96px" @submit.native.prevent>
        <el-form-item label="Agent 名称" required>
          <el-input v-model.trim="form.name" placeholder="例如：智能数据助手" />
        </el-form-item>
        <el-form-item label="Agent 类别" required>
          <el-select v-model.number="form.type" :disabled="Boolean(form.id)" style="width: 100%">
            <el-option :value="1" label="通用无状态 Agent" />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent 描述">
          <el-input v-model.trim="form.description" type="textarea" :rows="3" placeholder="简单介绍这个 Agent 的用途（选填）" />
        </el-form-item>
        <el-form-item label="对话模型" required>
          <el-select v-model.number="form.modelId" placeholder="请选择模型" style="width: 100%">
            <el-option
              v-for="model in models"
              :key="model.id"
              :label="model.name + '（' + model.modelname + '）'"
              :value="model.id"
            />
          </el-select>
          <div v-if="!models.length" class="af-form-tip">暂无可用对话模型，请先在模型列表中新建。</div>
        </el-form-item>
      </el-form>

      <span slot="footer">
        <el-button :disabled="saving" @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import AppSidebar from '../../components/AppSidebar.vue'
import { fetchAgents, fetchChatModels, getAgent, upsertAgent, deleteAgent } from './api'
import { fetchMcpPlugins } from '../mcp/api'

export default {
  name: 'AgentsPage',
  components: { AppSidebar },
  data() {
    return {
      navOpen: false,
      keyword: '',
      searchTimer: null,
      loading: false,
      saving: false,
      agents: [],
      models: [],
      currentPage: 1,
      pageSize: 12,
      totalCount: 0,
      totalPage: 0,
      dialogVisible: false,
      openingId: null,
      prefetchedIds: [],
      prefetchTimer: null,
      documentPrefetched: false,
      form: { id: null, name: '', type: 1, description: '', modelId: null }
    }
  },
  watch: {
    keyword() {
      clearTimeout(this.searchTimer)
      this.searchTimer = setTimeout(() => {
        this.currentPage = 1
        this.loadAgents()
      }, 300)
    }
  },
  created() {
    this.loadAgents()
    this.loadModels()
    // 空闲时预热配置文件，缩短跳转首屏等待
    setTimeout(() => this.prefetchConfigDocument(), 400)
  },
  beforeDestroy() {
    clearTimeout(this.searchTimer)
    clearTimeout(this.prefetchTimer)
  },
  methods: {
    async loadAgents() {
      this.loading = true
      try {
        const result = await fetchAgents({ page: this.currentPage, pageSize: this.pageSize, keyword: this.keyword.trim() })
        this.agents = result.data || []
        this.totalCount = Number(result.totalCount) || 0
        this.totalPage = Number(result.totalPage) || 0
        if (this.totalPage > 0 && this.currentPage > this.totalPage) {
          this.currentPage = this.totalPage
          return this.loadAgents()
        }
      } catch (e) {
        this.agents = []
        this.totalCount = 0
        this.totalPage = 0
        this.toast('加载 Agent 列表失败：' + e.message, true)
      } finally {
        this.loading = false
      }
    },
    async loadModels() {
      try {
        const result = await fetchChatModels()
        this.models = result.data || []
      } catch (e) {
        this.toast('加载模型列表失败：' + e.message, true)
      }
    },
    openCreate() {
      this.form = { id: null, name: '', type: 1, description: '', modelId: null }
      this.dialogVisible = true
    },
    openConfig(agent) {
      if (this.openingId) return
      this.openingId = agent.id
      document.body.style.cursor = 'progress'
      clearTimeout(this.prefetchTimer)
      // 兜底预热：即使没有 hover，也在跳转前把接口数据发出去
      this.prefetchAgent(agent)
      window.location.href = '/agents/config?id=' + agent.id
    },
    /** 悬停 140ms 后才预热，避免鼠标快速掠过时发起无意义的请求 */
    schedulePrefetch(agent) {
      if (!agent || this.prefetchedIds.includes(agent.id)) return
      clearTimeout(this.prefetchTimer)
      this.prefetchTimer = setTimeout(() => this.prefetchAgent(agent), 140)
    },
    /** 预热编辑页所需的三个接口，命中浏览器缓存后编辑页首屏几乎瞬时 */
    prefetchAgent(agent) {
      if (!agent || this.prefetchedIds.includes(agent.id)) return
      this.prefetchedIds.push(agent.id)
      getAgent(agent.id).catch(() => {})
      fetchChatModels().catch(() => {})
      fetchMcpPlugins('', true).catch(() => {})
    },
    /** 预热 /agents/config 文档，减少整页导航的等待 */
    prefetchConfigDocument() {
      if (this.documentPrefetched) return
      this.documentPrefetched = true
      const link = document.createElement('link')
      link.rel = 'prefetch'
      link.as = 'document'
      link.href = '/agents/config'
      document.head.appendChild(link)
    },
    async openEdit(agent) {
      try {
        const detail = await getAgent(agent.id)
        this.form = {
          id: detail.id,
          name: detail.name,
          type: detail.type || 1,
          description: detail.description || '',
          modelId: detail.modelId
        }
        this.dialogVisible = true
      } catch (e) {
        this.toast('加载 Agent 详情失败：' + e.message, true)
      }
    },
    closeDialog() {
      if (!this.saving) this.dialogVisible = false
    },
    async submit() {
      if (!this.form.name || !this.form.modelId) {
        this.toast('请填写 Agent 名称并选择模型', true)
        return
      }
      this.saving = true
      try {
        await upsertAgent(this.form)
        this.dialogVisible = false
        this.toast(this.form.id ? 'Agent 已更新' : 'Agent 已创建')
        this.loadAgents()
      } catch (e) {
        this.toast('保存失败：' + e.message, true)
      } finally {
        this.saving = false
      }
    },
    async onDelete(agent) {
      try {
        await this.$confirm('删除后该 Agent 及其配置将不可恢复，是否继续？', '删除 Agent「' + agent.name + '」', {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消'
        })
      } catch (_e) {
        return
      }
      try {
        await deleteAgent(agent.id)
        this.toast('Agent 已删除')
        this.loadAgents()
      } catch (e) {
        this.toast('删除失败：' + e.message, true)
      }
    },
    changePage(page) {
      if (page < 1 || page > this.totalPage || page === this.currentPage) return
      this.currentPage = page
      this.loadAgents()
    },
    changePageSize(size) {
      this.pageSize = size
      this.currentPage = 1
      this.loadAgents()
    },
    initial(name) {
      return (name || 'A').trim().charAt(0).toUpperCase()
    },
    formatTime(value) {
      return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
    },
    toast(text, error = false) {
      this.$message({ message: text, type: error ? 'error' : 'success', duration: 2400 })
    }
  }
}
</script>

<style lang="scss" scoped>
.af-agent-card {
  position: relative;
}

.af-agent-card--opening {
  cursor: progress;
  pointer-events: none;
}

// 点击进入时的即时反馈：半透明遮罩 + 转圈，避免用户以为没点上
.af-agent-card__mask {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: inherit;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(1.5px);
  color: #4b8ef7;
  font-size: 12.5px;
  font-weight: 600;

  i {
    font-size: 20px;
  }
}
</style>
