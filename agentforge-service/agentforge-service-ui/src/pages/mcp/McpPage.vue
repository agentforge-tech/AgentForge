<template>
  <div class="af-models" :class="{ 'af-models--nav-open': navOpen }">
    <div v-if="navOpen" class="af-sidebar-backdrop" @click="navOpen = false"></div>

    <AppSidebar active="mcp" />

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
          <template v-if="view === 'detail'">
            <a class="af-crumb__link" href="/mcp" @click.prevent="backToList">MCP 插件</a>
            <span class="af-crumb__sep">/</span>
            <span class="af-crumb__current">{{ (plugin && plugin.name) || '插件详情' }}</span>
          </template>
          <span v-else class="af-crumb__current">MCP 插件</span>
        </div>
        <div class="af-topbar__actions">
          <template v-if="view === 'list'">
            <button class="af-btn af-btn--icon af-btn--ghost" title="刷新" :disabled="loading" @click="loadPlugins">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v6h-6" />
              </svg>
            </button>
            <button class="af-btn af-btn--primary af-btn--sm" @click="openImport">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 3v12M7 10l5 5 5-5" /><path d="M4 21h16" />
              </svg>
              一键导入
            </button>
            <button class="af-btn af-btn--primary af-btn--sm" @click="openCreatePlugin">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <path d="M12 5v14M5 12h14" />
              </svg>
              新增 MCP 插件
            </button>
          </template>
          <template v-else>
            <button class="af-btn af-btn--sm" @click="backToList">← 返回列表</button>
            <button class="af-btn af-btn--sm mcp-export-btn" :disabled="exportDialog.loading" @click="openExport">快捷复制配置</button>
            <button class="af-btn af-btn--sm" @click="openEditPlugin(plugin)">编辑插件</button>
            <button class="af-btn af-btn--primary af-btn--sm" @click="openAddServer">新增 Server</button>
          </template>
        </div>
      </header>

      <section class="af-content">
        <div class="af-content__inner">
          <!-- 列表：MCP 插件卡片 -->
          <template v-if="view === 'list'">
            <div class="af-toolbar">
              <div class="af-search">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" />
                </svg>
                <input v-model="keyword" type="search" aria-label="搜索插件" placeholder="搜索插件名称或描述..." @input="loadPlugins" />
                <button v-if="keyword" class="af-search__clear" type="button" aria-label="清除搜索" @click="clearKeyword">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                    <path d="M18 6L6 18M6 6l12 12" />
                  </svg>
                </button>
              </div>
              <span class="mcp-count">{{ plugins.length }} 个 MCP 插件</span>
            </div>

            <div v-if="loading" class="af-loading"><span class="af-spinner"></span>正在加载 MCP 插件列表...</div>

            <div v-else-if="!plugins.length" class="af-empty">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="7" rx="2" /><rect x="3" y="13" width="18" height="7" rx="2" /><path d="M7 7.5h.01M7 16.5h.01M11 7.5h4M11 16.5h4" />
              </svg>
              <b>{{ keyword ? '未找到匹配的 MCP 插件' : '还没有 MCP 插件' }}</b>
              <p>新建插件并添加 HTTP MCP Server，或在 Agent 中绑定插件后即可调用其工具。</p>
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

          <!-- 详情：插件下的 Server 列表 -->
          <template v-else>
            <div v-if="pluginLoading" class="af-loading"><span class="af-spinner"></span>正在加载插件详情...</div>
            <template v-else-if="plugin">
              <div class="mcp-detail-head">
                <div class="mcp-detail-head__main">
                  <div class="mcp-detail-head__title">
                    <span class="mcp-avatar mcp-avatar--lg"><img v-if="plugin.icon" :src="plugin.icon" alt="" @error="onIconError" /><template v-else>M</template></span>
                    <div>
                      <h2>{{ plugin.name }}</h2>
                      <p>{{ plugin.description || '暂无插件描述' }}</p>
                    </div>
                  </div>
                  <span class="af-tag" :class="plugin.status === 1 ? 'af-tag--success' : 'af-tag--muted'">{{ plugin.status === 1 ? '插件已启用' : '插件已禁用' }}</span>
                </div>
                <div class="mcp-detail-head__stat">
                  <span>Server 总数</span>
                  <b>{{ page.total }}</b>
                </div>
              </div>

              <div class="af-toolbar mcp-server-toolbar">
                <div class="af-search">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" />
                  </svg>
                  <input v-model.trim="serverQuery.serverName" type="search" placeholder="搜索 Server 名称" @input="searchServers" />
                </div>
                <select v-model="serverQuery.transportType" class="af-select mcp-filter" @change="searchServers">
                  <option value="">全部传输协议</option>
                  <option :value="1">Streamable HTTP</option>
                  <option :value="2">SSE</option>
                </select>
                <select v-model="serverQuery.status" class="af-select mcp-filter" @change="searchServers">
                  <option value="">全部状态</option>
                  <option :value="1">已启用</option>
                  <option :value="0">已禁用</option>
                </select>
                <button class="af-btn af-btn--sm" :disabled="serverLoading" @click="loadServers">刷新</button>
              </div>

              <div class="mcp-table-wrap">
                <table class="mcp-table">
                  <thead>
                    <tr>
                      <th>Server 名称</th>
                      <th>服务地址</th>
                      <th class="mcp-col-transport">传输协议</th>
                      <th class="mcp-col-headers">请求头</th>
                      <th class="mcp-col-status">状态</th>
                      <th class="mcp-col-ops">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="serverLoading">
                      <td colspan="6" class="mcp-table__empty">正在加载 Server 列表...</td>
                    </tr>
                    <tr v-else-if="!servers.length">
                      <td colspan="6" class="mcp-table__empty">暂无 Server 配置，点击右上角“新增 Server”添加。</td>
                    </tr>
                    <tr v-for="server in servers" :key="server.id">
                      <td><span class="mcp-server-name" :title="server.serverName">{{ server.serverName }}</span></td>
                      <td><span class="mcp-server-url" :title="server.serverUrl">{{ server.serverUrl }}</span></td>
                      <td><span class="af-tag af-tag--muted">{{ transportLabel(server.transportType) }}</span></td>
                      <td>
                        <span v-if="headerCount(server.headers)" class="mcp-headers-cell" :title="prettyHeaders(server.headers)">{{ headerCount(server.headers) }} 项</span>
                        <span v-else class="mcp-muted">未配置</span>
                      </td>
                      <td>
                        <button type="button" class="mcp-switch" :class="{ 'mcp-switch--on': server.status === 1 }" :aria-pressed="server.status === 1" @click="toggleServerStatus(server)">
                          <span class="mcp-switch__knob"></span>
                        </button>
                      </td>
                      <td>
                        <div class="mcp-row-ops">
                          <button class="af-mini-btn" :disabled="testingServer === server.id" @click="onTestServer(server)">{{ testingServer === server.id ? '探测中' : '工具列表' }}</button>
                          <button class="af-mini-btn" @click="openEditServer(server)">编辑</button>
                          <button class="af-mini-btn af-mini-btn--danger" @click="onDeleteServer(server)">删除</button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div v-if="page.total > 0" class="mcp-pager">
                <span class="mcp-pager__info">共 {{ page.total }} 条 · 第 {{ page.page }} / {{ page.totalPage }} 页</span>
                <div class="mcp-pager__ops">
                  <select v-model.number="page.pageSize" class="af-select mcp-filter mcp-filter--sm" @change="searchServers">
                    <option :value="10">10 条/页</option>
                    <option :value="20">20 条/页</option>
                    <option :value="50">50 条/页</option>
                  </select>
                  <button class="af-btn af-btn--sm" :disabled="page.page <= 1 || serverLoading" @click="gotoPage(page.page - 1)">上一页</button>
                  <button class="af-btn af-btn--sm" :disabled="page.page >= page.totalPage || serverLoading" @click="gotoPage(page.page + 1)">下一页</button>
                </div>
              </div>
            </template>
          </template>
        </div>
      </section>
    </main>

    <!-- 插件弹窗 -->
    <div v-if="pluginDialog.visible" class="af-modal">
      <div class="af-modal__backdrop" @click="closePluginDialog"></div>
      <div class="af-modal__panel mcp-dialog" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">{{ pluginDialog.form.id ? '编辑 MCP 插件' : '新增 MCP 插件' }}</h2><button class="af-modal__close" @click="closePluginDialog">×</button></div>
        <div class="af-modal__body mcp-dialog__body">
          <div class="af-field"><label class="af-field__label">插件名称 <i>*</i></label><input v-model.trim="pluginDialog.form.name" class="af-input" maxlength="128" placeholder="例如：地图服务" /></div>
          <div class="af-field"><label class="af-field__label">图标地址</label><input v-model.trim="pluginDialog.form.icon" class="af-input" placeholder="https://...（选填）" /></div>
          <div class="af-field"><label class="af-field__label">描述</label><textarea v-model.trim="pluginDialog.form.description" class="af-input" rows="3" maxlength="1024" placeholder="简单介绍插件用途（选填）"></textarea></div>
          <div class="af-field"><label class="af-field__label">状态</label>
            <div class="af-switch-row">
              <button type="button" class="af-chip" :class="{ 'af-chip--active': pluginDialog.form.status === 1 }" @click="pluginDialog.form.status = 1">启用</button>
              <button type="button" class="af-chip" :class="{ 'af-chip--active': pluginDialog.form.status === 0 }" @click="pluginDialog.form.status = 0">禁用</button>
            </div>
          </div>
        </div>
        <div class="af-modal__foot">
          <button class="af-btn" @click="closePluginDialog">取消</button>
          <button class="af-btn af-btn--primary" :disabled="pluginDialog.saving" @click="savePlugin">{{ pluginDialog.saving ? '保存中...' : '确定' }}</button>
        </div>
      </div>
    </div>

    <!-- Server 弹窗 -->
    <div v-if="serverDialog.visible" class="af-modal">
      <div class="af-modal__backdrop" @click="closeServerDialog"></div>
      <div class="af-modal__panel mcp-dialog mcp-dialog--wide" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">{{ serverDialog.form.id ? '编辑 MCP Server' : '新增 MCP Server' }}</h2><button class="af-modal__close" @click="closeServerDialog">×</button></div>
        <div class="af-modal__body mcp-dialog__body">
          <div class="af-field"><label class="af-field__label">Server 名称 <i>*</i></label><input v-model.trim="serverDialog.form.serverName" class="af-input" maxlength="128" placeholder="例如：deepwiki" /></div>
          <div class="af-field"><label class="af-field__label">服务地址 URL <i>*</i></label><input v-model.trim="serverDialog.form.serverUrl" class="af-input" placeholder="https://mcp.example.com/mcp" />
            <div class="af-hint">当前支持 Streamable HTTP / SSE（https:// 或 http://）。</div>
          </div>
          <div class="af-field"><label class="af-field__label">传输协议</label>
            <select v-model.number="serverDialog.form.transportType" class="af-select">
              <option :value="1">Streamable HTTP</option>
              <option :value="2">SSE</option>
            </select>
          </div>
          <div class="af-field"><label class="af-field__label">请求头 Headers（JSON）</label><textarea v-model.trim="serverDialog.form.headersText" class="af-input mcp-headers" rows="8" placeholder='{ "Authorization": "Bearer xxx" }'></textarea>
            <div class="af-hint">保存后仅显示脱敏内容，重新保存时会覆盖原值。</div>
          </div>
          <div class="af-field"><label class="af-field__label">状态</label>
            <div class="af-switch-row">
              <button type="button" class="af-chip" :class="{ 'af-chip--active': serverDialog.form.status === 1 }" @click="serverDialog.form.status = 1">启用</button>
              <button type="button" class="af-chip" :class="{ 'af-chip--active': serverDialog.form.status === 0 }" @click="serverDialog.form.status = 0">禁用</button>
            </div>
          </div>
        </div>
        <div class="af-modal__foot">
          <button class="af-btn" @click="closeServerDialog">取消</button>
          <button class="af-btn af-btn--primary" :disabled="serverDialog.saving" @click="saveServer">{{ serverDialog.saving ? '保存中...' : '确定' }}</button>
        </div>
      </div>
    </div>

    <!-- 导入弹窗 -->
    <div v-if="importDialog.visible" class="af-modal">
      <div class="af-modal__backdrop" @click="closeImportDialog"></div>
      <div class="af-modal__panel mcp-dialog mcp-dialog--wide" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">一键导入 MCP 插件</h2><button class="af-modal__close" @click="closeImportDialog">×</button></div>
        <div class="af-modal__body mcp-dialog__body">
          <div class="af-field"><label class="af-field__label">插件名称 <i>*</i></label><input v-model.trim="importDialog.form.name" class="af-input" maxlength="128" placeholder="例如：高德 MCP" /></div>
          <div class="af-field"><label class="af-field__label">配置 JSON <i>*</i></label><textarea v-model.trim="importDialog.form.jsonText" class="af-input mcp-headers mcp-json-input" placeholder='{
  "mcpServers": {
    "deepwiki": {
      "url": "https://mcp.deepwiki.com/mcp",
      "headers": {}
    }
  }
}'></textarea>
            <div class="af-hint">支持主流 MCP 配置片段；仅导入包含 url 的 HTTP 服务。</div>
          </div>
        </div>
        <div class="af-modal__foot">
          <button class="af-btn" @click="closeImportDialog">取消</button>
          <button class="af-btn af-btn--primary" :disabled="importDialog.importing" @click="doImport">{{ importDialog.importing ? '导入中...' : '确定导入' }}</button>
        </div>
      </div>
    </div>

    <!-- 工具探测弹窗 -->
    <div v-if="toolsDialog.visible" class="af-modal">
      <div class="af-modal__backdrop" @click="closeToolsDialog"></div>
      <div class="af-modal__panel mcp-dialog mcp-dialog--wide" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">{{ toolsDialog.serverName }} · 工具列表</h2><button class="af-modal__close" @click="closeToolsDialog">×</button></div>
        <div class="af-modal__body mcp-dialog__body">
          <div v-if="toolsDialog.loading" class="af-loading"><span class="af-spinner"></span>正在连接服务并获取工具...</div>
          <div v-else-if="!toolsDialog.ok" class="mcp-import-result mcp-import-result--error"><b>连接失败</b><p>{{ toolsDialog.error }}</p></div>
          <div v-else-if="!toolsDialog.tools.length" class="af-empty af-empty--sm"><b>该服务未返回任何工具</b></div>
          <ul v-else class="mcp-tool-list">
            <li v-for="tool in toolsDialog.tools" :key="tool.name" class="mcp-tool-item">
              <b>{{ tool.name }}</b>
              <p>{{ tool.description || '暂无描述' }}</p>
            </li>
          </ul>
        </div>
        <div class="af-modal__foot">
          <button class="af-btn" @click="closeToolsDialog">关闭</button>
          <button class="af-btn af-btn--primary" :disabled="toolsDialog.loading" @click="retryTools">重新探测</button>
        </div>
      </div>
    </div>

    <!-- 快捷复制导出弹窗 -->
    <div v-if="exportDialog.visible" class="af-modal">
      <div class="af-modal__backdrop" @click="exportDialog.visible = false"></div>
      <div class="af-modal__panel mcp-dialog mcp-dialog--wide" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">快捷复制配置</h2><button class="af-modal__close" @click="exportDialog.visible = false">×</button></div>
        <div class="af-modal__body mcp-dialog__body">
          <div class="mcp-export-tip">配置中可能包含鉴权信息（已脱敏展示），请妥善保管并仅提供给可信应用。</div>
          <textarea class="af-input mcp-headers mcp-json-input mcp-export-json" readonly :value="exportDialog.json"></textarea>
        </div>
        <div class="af-modal__foot">
          <button class="af-btn" @click="exportDialog.visible = false">关闭</button>
          <button class="af-btn af-btn--primary" :disabled="!exportDialog.json" @click="copyExport">复制配置</button>
        </div>
      </div>
    </div>

    <div v-if="tip" class="af-toast af-toast--show" :class="{ 'af-toast--error': tipError }">{{ tip }}</div>
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
      // list：插件卡片列表；detail：某个插件的 Server 列表
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
      testingServer: null,
      tip: '',
      tipError: false,
      tipTimer: null
    }
  },
  async created() {
    this.pluginId = resolvePluginId()
    if (this.pluginId) await this.openDetail(this.pluginId, { push: false })
    else await this.loadPlugins()
    window.addEventListener('popstate', this.handlePopState)
  },
  beforeDestroy() {
    clearTimeout(this.tipTimer)
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
    clearKeyword() {
      this.keyword = ''
      this.loadPlugins()
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
      this.pluginDialog.form = { id: plugin.id, name: plugin.name, icon: plugin.icon || '', description: plugin.description || '', status: plugin.status }
      this.pluginDialog.visible = true
    },
    closePluginDialog() { this.pluginDialog.visible = false },
    async savePlugin() {
      const form = this.pluginDialog.form
      if (!form.name) return this.toast('请填写插件名称', true)
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
      if (!window.confirm(`确认删除插件「${plugin.name}」？其下所有 Server 将一并删除。`)) return
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
    openAddServer() {
      this.serverDialog.form = { id: null, pluginId: this.pluginId, serverName: '', serverUrl: '', transportType: 1, headersText: '', status: 1 }
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
    closeServerDialog() { this.serverDialog.visible = false },
    async saveServer() {
      const form = this.serverDialog.form
      if (!form.serverName || !form.serverUrl) return this.toast('请填写 Server 名称与地址', true)
      if (form.headersText) {
        try { JSON.parse(form.headersText) } catch (e) { return this.toast('Headers 不是合法 JSON：' + e.message, true) }
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
      if (!window.confirm(`确认删除 MCP Server「${server.serverName}」？`)) return
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
      this.toolsDialog = { ...this.toolsDialog, visible: true, loading: true, ok: false, error: '', tools: [], serverId: server.id, serverName: server.serverName }
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
    closeToolsDialog() { this.toolsDialog.visible = false },
    // ---------- 导入 / 导出 ----------
    openImport() {
      this.importDialog.form = { name: this.keyword || '', jsonText: '' }
      this.importDialog.visible = true
    },
    closeImportDialog() { this.importDialog.visible = false },
    async doImport() {
      const form = this.importDialog.form
      if (!form.name) return this.toast('请填写插件名称', true)
      let payload
      try { payload = JSON.parse(form.jsonText) } catch (e) { return this.toast('JSON 解析失败：' + e.message, true) }
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
      try { return JSON.stringify(headers, null, 2) } catch (_e) { return '未配置请求头' }
    },
    toast(text, error = false) {
      this.tip = text
      this.tipError = error
      clearTimeout(this.tipTimer)
      this.tipTimer = setTimeout(() => { this.tip = '' }, 2800)
    }
  }
}
</script>

<style lang="scss">
@use '../models/styles/tokens' as *;
.mcp-count { color: $color-text-muted; font-size: 12px; white-space: nowrap; }
.mcp-avatar { display: flex; align-items: center; justify-content: center; overflow: hidden; background: linear-gradient(145deg, #6b9fe8, #4b7fd6); color: #fff; font-weight: 700; font-size: 16px; img { width: 100%; height: 100%; object-fit: cover; } }
.mcp-avatar--lg { width: 46px; height: 46px; flex: 0 0 46px; border-radius: 12px; font-size: 18px; }
.mcp-desc { min-height: 38px; }
.mcp-card { cursor: pointer; transition: box-shadow .16s ease, transform .16s ease; &:hover { box-shadow: 0 8px 22px rgba(31, 56, 88, .1); transform: translateY(-1px); } }

/* ---------- 详情页 ---------- */
.mcp-detail-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; padding: 16px 18px; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: $shadow-xs; }
.mcp-detail-head__main { display: flex; min-width: 0; align-items: center; gap: 14px; }
.mcp-detail-head__title { display: flex; min-width: 0; align-items: center; gap: 12px; h2 { margin: 0; color: $color-text; font-size: 16px; font-weight: 700; } p { margin: 4px 0 0; max-width: 640px; color: $color-text-muted; font-size: 12px; line-height: 1.6; } }
.mcp-detail-head__stat { display: flex; flex-shrink: 0; flex-direction: column; align-items: center; gap: 2px; padding: 8px 16px; border-radius: 10px; background: $color-sidebar; span { color: $color-text-muted; font-size: 11px; } b { color: $color-brand; font-size: 18px; } }
.mcp-server-toolbar { justify-content: flex-start; align-items: center; gap: 10px; flex-wrap: wrap; }
.mcp-server-toolbar .af-search { flex: 0 1 320px; min-width: 180px; max-width: 320px; }
.mcp-server-toolbar .mcp-filter,
.mcp-pager .mcp-filter { width: 150px; height: 34px; flex: 0 0 auto; font-size: 12.5px; }
.mcp-pager .mcp-filter--sm { width: 104px; height: 28px; font-size: 12px; }
.mcp-table-wrap { overflow: hidden; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: $shadow-xs; }
.mcp-table { width: 100%; border-collapse: collapse; font-size: 12.5px; th, td { padding: 11px 14px; text-align: left; border-bottom: 1px solid $color-border; vertical-align: middle; } thead th { background: $color-sidebar; color: $color-text-secondary; font-size: 12px; font-weight: 700; white-space: nowrap; } tbody tr:last-child td { border-bottom: 0; } tbody tr:hover td { background: #fafcff; } }
.mcp-col-transport { width: 150px; }
.mcp-col-headers { width: 110px; }
.mcp-col-status { width: 90px; }
.mcp-col-ops { width: 220px; }
.mcp-table__empty { padding: 34px 14px; color: $color-text-muted; text-align: center; }
.mcp-server-name { display: block; max-width: 220px; overflow: hidden; color: $color-text; font-family: $font-mono; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.mcp-server-url { display: block; max-width: 420px; overflow: hidden; color: $color-text-secondary; font-family: $font-mono; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.mcp-headers-cell { color: #2f6bc4; cursor: help; font-size: 12px; }
.mcp-muted { color: $color-text-muted; font-size: 12px; }
.mcp-row-ops { display: flex; flex-wrap: wrap; gap: 6px; }
.mcp-switch { position: relative; width: 38px; height: 20px; padding: 0; border: 0; border-radius: 999px; background: #d3dbe6; cursor: pointer; transition: background .18s ease; &__knob { position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(31, 56, 88, .28); transition: transform .18s ease; } &--on { background: #24b36b; .mcp-switch__knob { transform: translateX(18px); } } }
.mcp-pager { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.mcp-pager__info { color: $color-text-muted; font-size: 12px; }
.mcp-pager__ops { display: flex; align-items: center; gap: 8px; }

/* ---------- 弹窗 ---------- */
.mcp-dialog { width: min(560px, calc(100vw - 32px)); &--wide { width: min(760px, calc(100vw - 32px)); } &__body { display: flex; flex-direction: column; gap: 14px; max-height: 60vh; overflow-y: auto; } .af-modal__foot { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid $color-border; } }
.mcp-dialog--wide .mcp-dialog__body { max-height: 72vh; }
.af-switch-row { display: flex; gap: 8px; }
.mcp-headers { min-height: 168px; padding: 10px 12px; font-family: $font-mono; font-size: 12.5px; line-height: 1.7; resize: vertical; }
.mcp-json-input { min-height: 320px; }
.mcp-export-btn { border-color: #b9d2f5; background: #f4f8ff; color: #286bc5; }
.mcp-export-tip { padding: 9px 12px; border: 1px solid #f3ddb3; border-radius: 8px; background: #fffaef; color: #8a6320; font-size: 12px; line-height: 1.6; }
.mcp-export-json { color: $color-text-secondary; }
.mcp-import-result { padding: 10px 12px; border: 1px solid #bfe3cd; border-radius: 8px; background: #f2fbf5; b { color: #1d8a55; font-size: 12.5px; } p { margin: 6px 0 0; color: #c9423a; font-size: 12px; word-break: break-all; } &--error { border-color: #f0c4c0; background: #fff5f4; b { color: #c9423a; } } }
.mcp-tool-list { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 8px; }
.mcp-tool-item { padding: 10px 12px; border: 1px solid $color-border; border-radius: 8px; background: $color-sidebar; b { color: $color-text; font-family: $font-mono; font-size: 12.5px; } p { margin: 4px 0 0; color: $color-text-secondary; font-size: 12px; line-height: 1.6; } }
.af-mini-btn { padding: 3px 8px; border: 1px solid transparent; border-radius: 5px; background: transparent; color: $color-text-secondary; cursor: pointer; font-size: 11.5px; transition: all .15s; &:hover { border-color: #b9d2f5; background: #f0f6ff; color: #2f6bc4; } &--danger:hover { border-color: #f0c4c0; background: #fff3f2; color: #c9423a; } &:disabled { cursor: not-allowed; opacity: .5; } }
.af-empty { display: flex; flex-direction: column; align-items: center; padding: 64px 16px; text-align: center; color: $color-text-muted; svg { width: 40px; height: 40px; margin-bottom: 12px; color: #aeb9c8; } b { color: $color-text-secondary; font-size: 14px; } p { margin: 6px 0 0; max-width: 320px; font-size: 12px; line-height: 1.7; } .af-btn { margin-top: 14px; } &--sm { padding: 24px 12px; } }
.af-tag--success { border-color: #bfe3cd; background: #eaf7f0; color: #1d8a55; }
.af-tag--muted { border-color: $color-border; background: $color-sidebar; color: $color-text-muted; }
.af-crumb__link { color: $color-text-secondary; text-decoration: none; &:hover { color: $color-brand; } }
</style>
