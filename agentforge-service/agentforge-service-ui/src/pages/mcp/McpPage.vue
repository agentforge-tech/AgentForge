<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>

    <AppSidebar active="mcp" />

    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <el-button class="af-mobile-menu" type="text" icon="el-icon-s-fold" aria-label="打开导航菜单" @click="navOpen = true" />
          <span>我的资源库</span>
          <span class="af-crumb__sep">/</span>
          <template v-if="view === 'detail'">
            <a class="af-crumb__link" href="/mcp" @click.prevent="backToList">MCP 插件</a>
            <span class="af-crumb__sep">/</span>
            <span class="af-crumb__current">{{ (plugin && plugin.name) || '插件详情' }}</span>
          </template>
          <span v-else class="af-crumb__current">MCP 插件</span>
        </div>
        <div class="af-topbar__actions">
          <template v-if="view === 'list'">
            <el-button icon="el-icon-refresh" :disabled="loading" @click="loadPlugins">刷新</el-button>
            <el-button icon="el-icon-download" @click="openImport">一键导入</el-button>
            <el-button type="primary" icon="el-icon-plus" @click="openCreatePlugin">新增 MCP 插件</el-button>
          </template>
          <template v-else>
            <el-button icon="el-icon-back" @click="backToList">返回列表</el-button>
            <el-button icon="el-icon-document-copy" :disabled="exportDialog.loading" @click="openExport">快捷复制配置</el-button>
            <el-button icon="el-icon-edit" @click="openEditPlugin(plugin)">编辑插件</el-button>
            <el-button type="primary" icon="el-icon-plus" @click="openAddServer">新增 Server</el-button>
          </template>
        </div>
      </header>

      <section class="af-content">
        <div class="af-content__inner">
          <!-- ------------------------- 列表：MCP 插件 ------------------------- -->
          <template v-if="view === 'list'">
            <div class="af-list-toolbar">
              <el-input
                v-model="keyword"
                class="af-list-toolbar__search"
                clearable
                prefix-icon="el-icon-search"
                placeholder="搜索插件名称或描述..."
                @input="loadPlugins"
              />
              <span class="af-list-toolbar__spacer"></span>
              <span class="mcp-count">{{ plugins.length }} 个 MCP 插件</span>
            </div>

            <div v-if="loading" class="af-loading"><span class="af-spinner"></span>正在加载 MCP 插件列表...</div>

            <div v-else-if="!plugins.length" class="af-empty">
              <span class="af-empty__icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="4" width="18" height="7" rx="2" /><rect x="3" y="13" width="18" height="7" rx="2" /><path d="M7 7.5h.01M7 16.5h.01M11 7.5h4M11 16.5h4" />
                </svg>
              </span>
              <div class="af-empty__title">{{ keyword ? '未找到匹配的 MCP 插件' : '还没有 MCP 插件' }}</div>
              <p class="af-empty__desc">新建插件并添加 HTTP MCP Server，或在 Agent 中绑定插件后即可调用其工具。</p>
              <button class="af-btn af-btn--primary af-btn--sm" @click="openCreatePlugin">创建第一个 MCP 插件</button>
            </div>

            <div v-else class="af-grid">
              <article v-for="item in plugins" :key="item.id" class="af-model-card mcp-card" @click="enterPlugin(item)">
                <div class="af-model-card__head">
                  <span class="af-model-card__avatar mcp-avatar">
                    <img v-if="item.icon" :src="item.icon" alt="" @error="onIconError" />
                    <template v-else>M</template>
                  </span>
                  <div class="af-model-card__heading">
                    <div class="af-model-card__title">
                      <span class="af-model-card__name" :title="item.name">{{ item.name }}</span>
                      <span class="af-tag" :class="item.status === 1 ? 'af-tag--success' : 'af-tag--muted'">{{ item.status === 1 ? '已启用' : '已禁用' }}</span>
                    </div>
                    <div class="af-model-card__provider">点击进入 Server 列表</div>
                  </div>
                </div>

                <p class="af-model-card__desc mcp-desc">{{ item.description || '暂无插件描述' }}</p>

                <div class="af-model-card__footer">
                  <div class="af-model-card__meta">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <rect x="3" y="4" width="18" height="7" rx="2" /><path d="M7 7.5h.01M11 7.5h4" />
                    </svg>
                    <span>{{ (item.servers || []).length }} 个 MCP Server</span>
                  </div>
                  <div class="af-model-card__ops" @click.stop>
                    <button class="af-mini-btn" @click="togglePluginStatus(item)">{{ item.status === 1 ? '禁用' : '启用' }}</button>
                    <button class="af-mini-btn" @click="openEditPlugin(item)">编辑</button>
                    <button class="af-mini-btn af-mini-btn--danger" @click="onDeletePlugin(item)">删除</button>
                  </div>
                </div>
              </article>
            </div>
          </template>

          <!-- ------------------------- 详情：插件下的 Server ------------------------- -->
          <template v-else>
            <div v-loading="pluginLoading" class="af-panel mcp-detail-head">
              <template v-if="plugin">
                <div class="mcp-detail-head__main">
                  <span class="af-logo af-logo--lg">
                    <img v-if="plugin.icon" :src="plugin.icon" alt="" @error="onIconError" />
                    <template v-else>M</template>
                  </span>
                  <div class="mcp-detail-head__text">
                    <div class="mcp-detail-head__title">
                      <h2>{{ plugin.name }}</h2>
                      <el-tag size="mini" :type="plugin.status === 1 ? 'success' : 'info'" effect="plain">
                        {{ plugin.status === 1 ? '已启用' : '已禁用' }}
                      </el-tag>
                    </div>
                    <p>{{ plugin.description || '暂无插件描述' }}</p>
                  </div>
                </div>
                <div class="mcp-detail-head__stat">
                  <span>Server 总数</span>
                  <b>{{ page.total }}</b>
                </div>
              </template>
            </div>

            <div class="af-list-toolbar">
              <el-input
                v-model.trim="serverQuery.serverName"
                class="af-list-toolbar__search"
                clearable
                prefix-icon="el-icon-search"
                placeholder="搜索 Server 名称"
                @input="searchServers"
              />
              <el-select v-model="serverQuery.transportType" placeholder="全部传输协议" @change="searchServers">
                <el-option label="全部传输协议" value="" />
                <el-option label="Streamable HTTP" :value="1" />
                <el-option label="SSE" :value="2" />
              </el-select>
              <el-select v-model="serverQuery.status" placeholder="全部状态" @change="searchServers">
                <el-option label="全部状态" value="" />
                <el-option label="已启用" :value="1" />
                <el-option label="已禁用" :value="0" />
              </el-select>
              <el-button icon="el-icon-refresh" :disabled="serverLoading" @click="loadServers">刷新</el-button>
            </div>

            <div class="af-table-card">
              <el-table v-loading="serverLoading" :data="servers" row-key="id" style="width: 100%">
                <el-table-column label="Server 名称" min-width="180" show-overflow-tooltip>
                  <template slot-scope="{ row }"><span class="mcp-code">{{ row.serverName }}</span></template>
                </el-table-column>
                <el-table-column label="服务地址" min-width="260" show-overflow-tooltip>
                  <template slot-scope="{ row }"><span class="mcp-code mcp-code--url">{{ row.serverUrl }}</span></template>
                </el-table-column>
                <el-table-column label="传输协议" width="150">
                  <template slot-scope="{ row }">
                    <el-tag size="mini" type="info" effect="plain">{{ transportLabel(row.transportType) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="请求头" width="110">
                  <template slot-scope="{ row }">
                    <el-tooltip v-if="headerCount(row.headers)" :content="prettyHeaders(row.headers)" placement="top">
                      <span class="mcp-headers-cell">{{ headerCount(row.headers) }} 项</span>
                    </el-tooltip>
                    <span v-else class="af-cell__sub">未配置</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template slot-scope="{ row }">
                    <el-switch :value="row.status === 1" @change="toggleServerStatus(row)" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="230" fixed="right">
                  <template slot-scope="{ row }">
                    <el-button type="text" :loading="testingServer === row.id" @click="onTestServer(row)">工具列表</el-button>
                    <el-button type="text" @click="openEditServer(row)">编辑</el-button>
                    <el-button type="text" class="af-text-danger" @click="onDeleteServer(row)">删除</el-button>
                  </template>
                </el-table-column>

                <template slot="empty">
                  <el-empty description="暂无 Server 配置，点击右上角「新增 Server」添加。" />
                </template>
              </el-table>
            </div>

            <div v-if="page.total > 0" class="af-pager af-pager--end">
              <span>共 {{ page.total }} 条 · 第 {{ page.page }} / {{ page.totalPage }} 页</span>
              <el-pagination
                background
                layout="sizes, prev, pager, next, jumper"
                :page-sizes="[10, 20, 50]"
                :page-size="page.pageSize"
                :current-page="page.page"
                :total="page.total"
                @size-change="changeServerPageSize"
                @current-change="gotoPage"
              />
            </div>
          </template>
        </div>
      </section>
    </main>

    <!-- ------------------------------ 插件弹窗 ------------------------------ -->
    <el-dialog
      :visible.sync="pluginDialog.visible"
      :title="pluginDialog.form.id ? '编辑 MCP 插件' : '新增 MCP 插件'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form :model="pluginDialog.form" label-width="88px" @submit.native.prevent>
        <el-form-item label="插件名称" required>
          <el-input v-model.trim="pluginDialog.form.name" maxlength="128" placeholder="例如：地图服务" />
        </el-form-item>
        <el-form-item label="图标地址">
          <el-input v-model.trim="pluginDialog.form.icon" placeholder="https://...（选填）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model.trim="pluginDialog.form.description" type="textarea" :rows="3" maxlength="1024" placeholder="简单介绍插件用途（选填）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="pluginDialog.form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="closePluginDialog">取消</el-button>
        <el-button type="primary" :loading="pluginDialog.saving" @click="savePlugin">确定</el-button>
      </span>
    </el-dialog>

    <!-- ------------------------------ Server 弹窗 ------------------------------ -->
    <el-dialog
      :visible.sync="serverDialog.visible"
      :title="serverDialog.form.id ? '编辑 MCP Server' : '新增 MCP Server'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form :model="serverDialog.form" label-width="110px" @submit.native.prevent>
        <el-form-item label="Server 名称" required>
          <el-input v-model.trim="serverDialog.form.serverName" maxlength="128" placeholder="例如：deepwiki" />
        </el-form-item>
        <el-form-item label="服务地址 URL" required>
          <el-input v-model.trim="serverDialog.form.serverUrl" placeholder="https://mcp.example.com/mcp" />
          <div class="af-form-tip">当前支持 Streamable HTTP / SSE（https:// 或 http://）。</div>
        </el-form-item>
        <el-form-item label="传输协议">
          <el-select v-model.number="serverDialog.form.transportType" style="width: 100%">
            <el-option label="Streamable HTTP" :value="1" />
            <el-option label="SSE" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求头 Headers">
          <el-input
            v-model.trim="serverDialog.form.headersText"
            class="mcp-json-input"
            type="textarea"
            :rows="8"
            placeholder='{ "Authorization": "Bearer xxx" }'
          />
          <div class="af-form-tip">保存后仅显示脱敏内容，重新保存时会覆盖原值。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="serverDialog.form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="closeServerDialog">取消</el-button>
        <el-button type="primary" :loading="serverDialog.saving" @click="saveServer">确定</el-button>
      </span>
    </el-dialog>

    <!-- ------------------------------ 导入弹窗 ------------------------------ -->
    <el-dialog :visible.sync="importDialog.visible" title="一键导入 MCP 插件" width="720px" :close-on-click-modal="false">
      <el-form :model="importDialog.form" label-width="96px" @submit.native.prevent>
        <el-form-item label="插件名称" required>
          <el-input v-model.trim="importDialog.form.name" maxlength="128" placeholder="例如：高德 MCP" />
        </el-form-item>
        <el-form-item label="配置 JSON" required>
          <el-input
            v-model.trim="importDialog.form.jsonText"
            class="mcp-json-input"
            type="textarea"
            :rows="14"
            placeholder='{ "mcpServers": { "deepwiki": { "url": "https://mcp.deepwiki.com/mcp" } } }'
          />
          <div class="af-form-tip">支持主流 MCP 配置片段；仅导入包含 url 的 HTTP 服务。</div>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="closeImportDialog">取消</el-button>
        <el-button type="primary" :loading="importDialog.importing" @click="doImport">确定导入</el-button>
      </span>
    </el-dialog>

    <!-- ------------------------------ 工具探测弹窗 ------------------------------ -->
    <el-dialog :visible.sync="toolsDialog.visible" :title="toolsDialog.serverName + ' · 工具列表'" width="760px">
      <div v-loading="toolsDialog.loading" class="mcp-tools-body">
        <el-alert
          v-if="!toolsDialog.loading && !toolsDialog.ok"
          type="error"
          :closable="false"
          show-icon
          title="连接失败"
          :description="toolsDialog.error || '无法获取工具列表'"
        />
        <el-empty v-else-if="!toolsDialog.loading && !toolsDialog.tools.length" description="该服务未返回任何工具" />
        <ul v-else-if="toolsDialog.tools.length" class="mcp-tool-list">
          <li v-for="tool in toolsDialog.tools" :key="tool.name" class="mcp-tool-item">
            <b>{{ tool.name }}</b>
            <p>{{ tool.description || '暂无描述' }}</p>
          </li>
        </ul>
      </div>
      <span slot="footer">
        <el-button @click="closeToolsDialog">关闭</el-button>
        <el-button type="primary" :loading="toolsDialog.loading" @click="retryTools">重新探测</el-button>
      </span>
    </el-dialog>

    <!-- ------------------------------ 快捷复制导出弹窗 ------------------------------ -->
    <el-dialog :visible.sync="exportDialog.visible" title="快捷复制配置" width="760px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="配置中可能包含鉴权信息（已脱敏展示），请妥善保管并仅提供给可信应用。"
      />
      <el-input
        v-model="exportDialog.json"
        class="mcp-json-input mcp-export-json"
        type="textarea"
        :rows="14"
        readonly
      />
      <span slot="footer">
        <el-button @click="exportDialog.visible = false">关闭</el-button>
        <el-button type="primary" :disabled="!exportDialog.json" @click="copyExport">复制配置</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import AppSidebar from '../../components/AppSidebar.vue'
import {
  deleteMcpPlugin,
  deleteMcpServer,
  exportMcpServers,
  fetchMcpPlugins,
  getMcpPlugin,
  getMcpServer,
  importMcpServers,
  pageMcpServers,
  testMcpServerTools,
  updateMcpPluginStatus,
  updateMcpServerStatus,
  upsertMcpPlugin,
  upsertMcpServer
} from './api'

/** 从地址栏读取插件 id，支持 /mcp?id=12 直接进入详情 */
function resolvePluginId() {
  const raw = new URLSearchParams(window.location.search).get('id')
  const id = Number(raw)
  return raw && !Number.isNaN(id) ? id : null
}

export default {
  name: 'McpPage',
  components: { AppSidebar },
  data() {
    return {
      navOpen: false,
      // list：插件列表；detail：某个插件的 Server 列表
      view: 'list',
      pluginId: null,
      loading: true,
      keyword: '',
      plugins: [],
      plugin: null,
      pluginLoading: false,
      servers: [],
      serverLoading: false,
      serverQuery: { serverName: '', transportType: '', status: '' },
      page: { page: 1, pageSize: 10, total: 0, totalPage: 0 },
      pluginDialog: { visible: false, saving: false, form: {} },
      serverDialog: { visible: false, saving: false, form: {} },
      importDialog: { visible: false, importing: false, form: { name: '', jsonText: '' } },
      toolsDialog: { visible: false, loading: false, ok: false, error: '', tools: [], serverId: null, serverName: '' },
      exportDialog: { visible: false, loading: false, json: '' },
      testingServer: null
    }
  },
  async created() {
    this.pluginId = resolvePluginId()
    if (this.pluginId) await this.openDetail(this.pluginId, { push: false })
    else await this.loadPlugins()
    window.addEventListener('popstate', this.handlePopState)
  },
  beforeDestroy() {
    window.removeEventListener('popstate', this.handlePopState)
  },
  methods: {
    // ---------- 视图切换 ----------
    async enterPlugin(item) {
      if (!item) return
      await this.openDetail(item.id)
    },
    async openDetail(id, { push = true } = {}) {
      this.view = 'detail'
      this.pluginId = id
      this.page = { ...this.page, page: 1, total: 0, totalPage: 0 }
      this.serverQuery = { serverName: '', transportType: '', status: '' }
      this.servers = []
      if (push) window.history.pushState({ id }, '', '/mcp?id=' + id)
      await Promise.all([this.loadPlugin(id), this.loadServers()])
    },
    backToList() {
      window.history.pushState({}, '', '/mcp')
      this.resetDetail()
      this.loadPlugins()
    },
    resetDetail() {
      this.view = 'list'
      this.pluginId = null
      this.plugin = null
      this.servers = []
    },
    async handlePopState() {
      const id = resolvePluginId()
      if (id) await this.openDetail(id, { push: false })
      else {
        this.resetDetail()
        await this.loadPlugins()
      }
    },
    // ---------- 插件 ----------
    async loadPlugins() {
      this.loading = true
      try {
        this.plugins = (await fetchMcpPlugins(this.keyword)) || []
      } catch (e) {
        this.toast('加载 MCP 插件失败：' + e.message, true)
      } finally {
        this.loading = false
      }
    },
    async loadPlugin(id) {
      this.pluginLoading = true
      try {
        this.plugin = await getMcpPlugin(id)
      } catch (e) {
        this.toast('加载插件详情失败：' + e.message, true)
        this.plugin = null
      } finally {
        this.pluginLoading = false
      }
    },
    openCreatePlugin() {
      this.pluginDialog.form = { id: null, name: '', icon: '', description: '', status: 1 }
      this.pluginDialog.visible = true
    },
    openEditPlugin(plugin) {
      if (!plugin) return
      this.pluginDialog.form = {
        id: plugin.id,
        name: plugin.name,
        icon: plugin.icon || '',
        description: plugin.description || '',
        status: plugin.status
      }
      this.pluginDialog.visible = true
    },
    closePluginDialog() {
      this.pluginDialog.visible = false
    },
    async savePlugin() {
      const form = this.pluginDialog.form
      if (!form.name) {
        this.toast('请填写插件名称', true)
        return
      }
      this.pluginDialog.saving = true
      try {
        const saved = await upsertMcpPlugin(form)
        this.pluginDialog.visible = false
        this.toast('MCP 插件已保存')
        if (this.view === 'detail') {
          if (!form.id && saved && saved.id) {
            await this.openDetail(saved.id)
          } else {
            await this.loadPlugin(this.pluginId)
          }
        }
        await this.loadPlugins()
      } catch (e) {
        this.toast('保存失败：' + e.message, true)
      } finally {
        this.pluginDialog.saving = false
      }
    },
    async togglePluginStatus(plugin) {
      const target = plugin.status === 1 ? 0 : 1
      try {
        await updateMcpPluginStatus(plugin.id, target)
        plugin.status = target
        if (this.plugin && this.plugin.id === plugin.id) this.plugin.status = target
        this.toast(target === 1 ? '插件已启用' : '插件已禁用')
      } catch (e) {
        this.toast('操作失败：' + e.message, true)
      }
    },
    async onDeletePlugin(plugin) {
      try {
        await this.$confirm('其下所有 Server 将一并删除，是否继续？', '删除插件「' + plugin.name + '」', {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消'
        })
      } catch (_e) {
        return
      }
      try {
        await deleteMcpPlugin(plugin.id)
        this.toast('MCP 插件已删除')
        if (this.view === 'detail' && this.pluginId === plugin.id) this.backToList()
        else await this.loadPlugins()
      } catch (e) {
        this.toast('删除失败：' + e.message, true)
      }
    },
    // ---------- Server ----------
    async loadServers() {
      if (!this.pluginId) return
      this.serverLoading = true
      try {
        const result = await pageMcpServers({
          pluginId: this.pluginId,
          serverName: this.serverQuery.serverName || null,
          transportType: this.serverQuery.transportType === '' ? null : Number(this.serverQuery.transportType),
          status: this.serverQuery.status === '' ? null : Number(this.serverQuery.status),
          page: this.page.page,
          pageSize: this.page.pageSize
        })
        this.servers = (result && result.data) || []
        this.page = {
          page: (result && result.currentPage) || 1,
          pageSize: (result && result.pageSize) || this.page.pageSize,
          total: (result && result.totalCount) || 0,
          totalPage: (result && result.totalPage) || 0
        }
      } catch (e) {
        this.toast('加载 Server 列表失败：' + e.message, true)
      } finally {
        this.serverLoading = false
      }
    },
    searchServers() {
      this.page = { ...this.page, page: 1 }
      this.loadServers()
    },
    gotoPage(page) {
      if (page < 1 || (this.page.totalPage && page > this.page.totalPage)) return
      this.page = { ...this.page, page }
      this.loadServers()
    },
    changeServerPageSize(size) {
      this.page = { ...this.page, page: 1, pageSize: size }
      this.loadServers()
    },
    openAddServer() {
      this.serverDialog.form = {
        id: null,
        pluginId: this.pluginId,
        serverName: '',
        serverUrl: '',
        transportType: 1,
        headersText: '',
        status: 1
      }
      this.serverDialog.visible = true
    },
    async openEditServer(server) {
      try {
        const detail = await getMcpServer(server.id)
        const headers = detail.rawHeaders || {}
        this.serverDialog.form = {
          id: detail.id,
          pluginId: detail.pluginId,
          serverName: detail.serverName,
          serverUrl: detail.serverUrl,
          transportType: detail.transportType || 1,
          headersText: Object.keys(headers).length ? JSON.stringify(headers, null, 2) : '',
          status: detail.status
        }
        this.serverDialog.visible = true
      } catch (e) {
        this.toast('加载 Server 详情失败：' + e.message, true)
      }
    },
    closeServerDialog() {
      this.serverDialog.visible = false
    },
    async saveServer() {
      const form = this.serverDialog.form
      if (!form.serverName || !form.serverUrl) {
        this.toast('请填写 Server 名称与地址', true)
        return
      }
      if (form.headersText) {
        try {
          JSON.parse(form.headersText)
        } catch (e) {
          this.toast('Headers 不是合法 JSON：' + e.message, true)
          return
        }
      }
      this.serverDialog.saving = true
      try {
        await upsertMcpServer({ ...form, headers: form.headersText ? JSON.parse(form.headersText) : null })
        this.serverDialog.visible = false
        this.toast('MCP Server 已保存')
        await this.loadServers()
        await this.loadPlugin(this.pluginId)
        await this.loadPlugins()
      } catch (e) {
        this.toast('保存失败：' + e.message, true)
      } finally {
        this.serverDialog.saving = false
      }
    },
    async toggleServerStatus(server) {
      const target = server.status === 1 ? 0 : 1
      try {
        await updateMcpServerStatus(server.id, target)
        server.status = target
        this.toast(target === 1 ? 'Server 已启用' : 'Server 已禁用')
      } catch (e) {
        this.toast('操作失败：' + e.message, true)
      }
    },
    async onDeleteServer(server) {
      try {
        await this.$confirm('删除后该 Server 的工具将不可再用，是否继续？', '删除 MCP Server「' + server.serverName + '」', {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消'
        })
      } catch (_e) {
        return
      }
      try {
        await deleteMcpServer(server.id)
        this.toast('MCP Server 已删除')
        await this.loadServers()
        await this.loadPlugin(this.pluginId)
        await this.loadPlugins()
      } catch (e) {
        this.toast('删除失败：' + e.message, true)
      }
    },
    // ---------- 工具探测 ----------
    async onTestServer(server) {
      this.testingServer = server.id
      this.toolsDialog = {
        ...this.toolsDialog,
        visible: true,
        loading: true,
        ok: false,
        error: '',
        tools: [],
        serverId: server.id,
        serverName: server.serverName
      }
      try {
        const result = await testMcpServerTools(server.id)
        this.toolsDialog.ok = Boolean(result.success)
        this.toolsDialog.error = result.message || ''
        this.toolsDialog.tools = result.tools || []
      } catch (e) {
        this.toolsDialog.ok = false
        this.toolsDialog.error = e.message
      } finally {
        this.toolsDialog.loading = false
        this.testingServer = null
      }
    },
    async retryTools() {
      if (this.toolsDialog.serverId == null) return
      await this.onTestServer({ id: this.toolsDialog.serverId, serverName: this.toolsDialog.serverName })
    },
    closeToolsDialog() {
      this.toolsDialog.visible = false
    },
    // ---------- 导入 / 导出 ----------
    openImport() {
      this.importDialog.form = { name: this.keyword || '', jsonText: '' }
      this.importDialog.visible = true
    },
    closeImportDialog() {
      this.importDialog.visible = false
    },
    async doImport() {
      const form = this.importDialog.form
      if (!form.name) {
        this.toast('请填写插件名称', true)
        return
      }
      let payload
      try {
        payload = JSON.parse(form.jsonText)
      } catch (e) {
        this.toast('JSON 解析失败：' + e.message, true)
        return
      }
      const mcpServers = payload && payload.mcpServers ? payload.mcpServers : payload
      this.importDialog.importing = true
      try {
        const result = await importMcpServers({ name: form.name, mcpServers })
        this.importDialog.visible = false
        await this.loadPlugins()
        const errors = (result && result.errors) || []
        const skipped = (result && result.skipped) || 0
        if (errors.length || skipped) {
          this.toast(`已导入 ${result.imported} 个，跳过 ${skipped + errors.length} 个：${errors[0] || '配置缺少 url'}`, true)
        } else {
          this.toast(`已导入 ${result.imported} 个 MCP Server`)
        }
      } catch (e) {
        this.toast('导入失败：' + e.message, true)
      } finally {
        this.importDialog.importing = false
      }
    },
    async openExport() {
      if (!this.pluginId) return
      this.exportDialog = { visible: true, loading: true, json: '' }
      try {
        const json = await exportMcpServers(this.pluginId)
        this.exportDialog.json = typeof json === 'string' ? json : JSON.stringify(json, null, 2)
      } catch (e) {
        this.toast('导出 MCP 配置失败：' + e.message, true)
        this.exportDialog.visible = false
      } finally {
        this.exportDialog.loading = false
      }
    },
    async copyExport() {
      const text = this.exportDialog.json
      if (!text) return
      try {
        await navigator.clipboard.writeText(text)
        this.toast('配置已复制')
      } catch (_e) {
        this.toast('复制失败，请手动选择文本', true)
      }
    },
    // ---------- 通用 ----------
    onIconError(event) {
      if (event && event.target) event.target.style.display = 'none'
    },
    transportLabel(type) {
      return Number(type) === 2 ? 'SSE' : 'Streamable HTTP'
    },
    headerCount(headers) {
      return headers ? Object.keys(headers).length : 0
    },
    prettyHeaders(headers) {
      if (!headers || !Object.keys(headers).length) return '未配置请求头'
      try {
        return JSON.stringify(headers, null, 2)
      } catch (_e) {
        return '未配置请求头'
      }
    },
    toast(text, error = false) {
      this.$message({ message: text, type: error ? 'error' : 'success', duration: 2800 })
    }
  }
}
</script>

<style lang="scss" scoped>
@use '../models/styles/tokens' as *;

.mcp-count {
  color: $color-text-muted;
  font-size: 12px;
  white-space: nowrap;
}

.af-tag--success {
  border-color: #bfe3cd;
  background: #eaf7f0;
  color: #1d8a55;
}

.af-tag--muted {
  border-color: $color-border;
  background: $color-sidebar;
  color: $color-text-muted;
}

.af-mini-btn {
  padding: 3px 8px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: $color-text-secondary;
  cursor: pointer;
  font-size: 11.5px;
  transition: all 0.15s;

  &:hover {
    border-color: #b9d2f5;
    background: #f0f6ff;
    color: #2f6bc4;
  }

  &--danger:hover {
    border-color: #f0c4c0;
    background: #fff3f2;
    color: #c9423a;
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
}

.mcp-card {
  cursor: pointer;
  transition: box-shadow 0.16s ease, transform 0.16s ease;

  &:hover {
    box-shadow: 0 8px 22px rgba(31, 56, 88, 0.1);
    transform: translateY(-1px);
  }
}

.mcp-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: linear-gradient(145deg, #6b9fe8, #4b7fd6);
  color: #fff;
  font-weight: 700;
  font-size: 16px;

  img {
    display: block;
    width: 100%;
    height: 100%;
    // 兜底：防止无固有尺寸的 SVG/超大图片把卡片撑变形
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
    background: #fff;
  }
}

// 空状态：撑满内容区剩余高度（底边贴到页面底部），图标交给全局 .af-empty__icon 控制尺寸
.af-empty {
  flex: 1;
  min-height: 320px;

  > svg {
    width: 24px;
    height: 24px;
    flex-shrink: 0;
  }

  &__desc {
    max-width: 420px;
    margin: 0;
    line-height: 1.7;
  }
}

.mcp-desc {
  min-height: 38px;
}

.af-text-danger {
  color: #d64545;

  &:hover,
  &:focus {
    color: #f56c6c;
  }
}

.af-crumb__link {
  color: $color-text-secondary;
  text-decoration: none;

  &:hover {
    color: $color-brand;
  }
}

.af-logo--lg {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  font-size: 18px;
}

.mcp-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;

  &__main {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 14px;
  }

  &__text {
    min-width: 0;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 10px;

    h2 {
      margin: 0;
      color: $color-text;
      font-size: 16px;
      font-weight: 700;
    }
  }

  p {
    margin: 4px 0 0;
    max-width: 640px;
    color: $color-text-muted;
    font-size: 12px;
    line-height: 1.6;
  }

  &__stat {
    display: flex;
    flex-shrink: 0;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 8px 16px;
    border-radius: 10px;
    background: $color-sidebar;

    span {
      color: $color-text-muted;
      font-size: 11px;
    }

    b {
      color: $color-brand;
      font-size: 18px;
    }
  }
}

.mcp-code {
  color: $color-text;
  font-family: $font-mono;
  font-size: 12.5px;
  font-weight: 600;

  &--url {
    color: $color-text-secondary;
    font-weight: 400;
    font-size: 12px;
  }
}

.mcp-headers-cell {
  color: #2f6bc4;
  cursor: help;
  font-size: 12px;
}

.mcp-json-input {
  ::v-deep .el-textarea__inner {
    font-family: $font-mono;
    font-size: 12.5px;
    line-height: 1.7;
  }
}

.mcp-export-json {
  margin-top: 12px;
}

.mcp-tools-body {
  min-height: 120px;
}

.mcp-tool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.mcp-tool-item {
  padding: 10px 12px;
  border: 1px solid $color-border;
  border-radius: 8px;
  background: $color-sidebar;

  b {
    color: $color-text;
    font-family: $font-mono;
    font-size: 12.5px;
  }

  p {
    margin: 4px 0 0;
    color: $color-text-secondary;
    font-size: 12px;
    line-height: 1.6;
  }
}
</style>
