<template>
  <div class="agent-config-page">
    <header class="agent-config-head">
      <a class="agent-config-back" href="/agents">← 返回我的 Agent</a>
      <div class="agent-config-title"><span class="agent-config-title__label">Agent：</span><strong>{{ form.name || '未命名 Agent' }}</strong></div>
      <div class="agent-config-head-actions">
        <a v-if="isPublished" class="af-btn agent-visit-btn" :href="publishedChatUrl" target="_blank" rel="noopener" :title="'最新发布时间：' + lastPublishTimeText"><span class="agent-visit-btn__label">访问发布页面 ↗</span><small class="agent-visit-btn__time">最新发布 {{ lastPublishTimeText }}</small></a>
        <template v-if="isPublished">
          <button class="af-btn agent-publish-btn" :disabled="publishing || saving || loading" @click="publish">{{ publishing === 'publish' ? '处理中...' : '重新发布' }}</button>
          <button class="af-btn agent-publish-btn agent-publish-btn--active" :disabled="publishing || saving || loading" @click="unpublish"><span v-if="publishing !== 'unpublish'" class="agent-publish-live-dot" aria-hidden="true"></span>{{ publishing === 'unpublish' ? '处理中...' : '停止发布' }}</button>
        </template>
        <button v-if="!isPublished" class="af-btn agent-publish-btn" :disabled="publishing || saving || loading" @click="publish">{{ publishing === 'publish' ? '处理中...' : '发布' }}</button>
        <div class="agent-save-actions">
          <button class="af-btn af-btn--primary" :disabled="saving || publishing || loading" @click="save(false)">{{ saving ? '保存中...' : '仅保存' }}</button>
          <button class="af-btn agent-save-version-btn" :disabled="saving || publishing || loading" @click="openVersionConfirm">保存并提交版本</button>
        </div>
        <button class="af-btn" :disabled="saving || publishing || loading" @click="openHistory">版本历史</button>
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
            <div class="agent-compare-label"><label class="af-field__label">MCP 插件</label><button type="button" class="af-btn af-btn--sm agent-mcp-pick-btn" @click="openMcpPicker">选择 MCP 插件</button></div>
            <div v-if="!form.mcpPlugins.length" class="agent-mcp-empty">未绑定 MCP 插件，Agent 仅使用内置工具。</div>
            <ul v-else class="agent-mcp-list">
              <li v-for="item in form.mcpPlugins" :key="item.pluginId" class="agent-mcp-item">
                <b>{{ mcpPluginName(item.pluginId) }}</b>
                <span>{{ (item.serverIds || []).length ? '已选 ' + item.serverIds.length + ' 个服务' : '全部服务' }}</span>
                <button type="button" class="af-mini-btn af-mini-btn--danger" @click="removeMcpPlugin(item.pluginId)">移除</button>
              </li>
            </ul>
          </div>
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

      <AgentDebugPanel
        :aid="detail.aid"
        :agent-name="form.name"
        :agent-avatar="detail.avatar || '/logo.png'"
      />
    </main>
    <div v-if="historyVisible" class="af-modal">
      <div class="af-modal__backdrop" @click="closeHistory"></div>
      <div class="af-modal__panel agent-version-panel" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">版本历史</h2><button class="af-modal__close" @click="closeHistory">×</button></div>
        <div class="af-modal__body agent-version-body">
          <div class="agent-version-tip">
            <span class="agent-version-tip__icon" aria-hidden="true">i</span>
            <div><b>切换版本只回填表单，不会保存</b><p>切换只会把该版本配置回填到编辑页，不会更新已保存的 Agent 配置。确认要使用该版本时，请点击右上角“仅保存”或“保存并提交版本”。</p></div>
          </div>
          <div v-if="versionLoading" class="af-loading"><span class="af-spinner"></span>正在加载版本...</div>
          <div v-else-if="!versions.length" class="agent-version-empty">暂无历史版本，请先使用“保存并提交版本”。</div>
          <div v-else class="agent-version-list">
            <div v-for="version in versions" :key="version.id" class="agent-version-item">
              <div class="agent-version-info">
                <b>V{{ version.versionNo }}</b><span>{{ formatTime(version.createTime) }}</span><small>{{ version.name }}</small>
                <p class="agent-version-remark">
                  <span class="agent-version-remark__label">备注</span>
                  <span v-if="version.versionRemark">{{ version.versionRemark }}</span>
                  <span v-else class="agent-version-remark__empty">暂无备注</span>
                </p>
              </div>
              <div class="agent-version-actions">
                <button class="af-btn af-btn--sm" :disabled="switchingVersion !== null || comparingVersion !== null" @click="openRemarkModal(version)">{{ version.versionRemark ? '编辑备注' : '添加备注' }}</button>
                <button class="af-btn af-btn--sm" :disabled="switchingVersion !== null || comparingVersion !== null" @click="switchToVersion(version)">{{ switchingVersion === version.versionNo ? '切换中...' : '切换版本' }}</button>
                <button class="af-btn af-btn--sm agent-version-compare-btn" :disabled="switchingVersion !== null || comparingVersion !== null" @click="compareWithCurrent(version)">{{ comparingVersion === version.versionNo ? '加载中...' : '对比版本' }}</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-if="compareVisible" class="af-modal agent-compare-modal">
      <div class="af-modal__backdrop" @click="closeCompare"></div>
      <div class="af-modal__panel agent-compare-panel" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">V{{ comparedVersionNo }} 与当前最新配置对比</h2><button class="af-modal__close" @click="closeCompare">×</button></div>
        <div class="af-modal__body agent-compare-body">
          <div class="agent-compare-toolbar">
            <span class="agent-compare-legend"><i></i>红色边框表示左右内容不一致</span>
            <span class="agent-compare-summary" :class="{ 'agent-compare-summary--same': comparisonChangedCount === 0 }">{{ comparisonChangedCount ? '共 ' + comparisonChangedCount + ' 处差异' : '两侧配置完全一致' }}</span>
          </div>
          <div class="agent-compare-columns">
            <article v-for="side in compareSides" :key="side.key" class="agent-compare-column">
              <div class="agent-compare-column-title"><b>{{ side.title }}</b><span>{{ side.hint }}</span></div>
              <section class="agent-config-panel agent-compare-prompt" :class="{ 'agent-compare-prompt--changed': comparisonChanged('systemPrompt') }">
                <div class="agent-config-panel__head">
                  <div><b>Prompt 提示词</b><p>定义 Agent 的角色、目标和回答规则</p></div>
                  <em v-if="comparisonChanged('systemPrompt')" class="agent-compare-badge">存在差异</em>
                </div>
                <pre class="agent-compare-prompt-text">{{ comparisonValue('systemPrompt', side.key) }}</pre>
              </section>
              <section class="agent-config-panel agent-compare-details">
                <div class="agent-config-panel__head"><div><b>详细配置</b><p>配置 Agent 的模型与记忆策略</p></div></div>
                <div class="agent-config-fields">
                  <div class="agent-config-overview">
                    <div class="agent-config-summary"><span>Agent ID</span><code>{{ detail.aid || '—' }}</code></div>
                    <div class="agent-config-summary"><span>Agent 类型</span><b>Standard ReAct Agent</b></div>
                  </div>
                  <div class="agent-config-group">
                    <div class="agent-config-group__title">基本配置</div>
                    <div class="af-field">
                      <div class="agent-compare-label"><label class="af-field__label">Agent 名称</label><em v-if="comparisonChanged('name')" class="agent-compare-badge">存在差异</em></div>
                      <input class="af-input agent-compare-input" :class="{ 'agent-compare-input--changed': comparisonChanged('name') }" readonly :value="comparisonValue('name', side.key)" />
                    </div>
                    <div class="af-field">
                      <div class="agent-compare-label"><label class="af-field__label">Agent 描述</label><em v-if="comparisonChanged('description')" class="agent-compare-badge">存在差异</em></div>
                      <textarea class="af-input agent-description-input agent-compare-input" :class="{ 'agent-compare-input--changed': comparisonChanged('description') }" rows="3" readonly :value="comparisonValue('description', side.key)"></textarea>
                    </div>
                  </div>
                  <div class="af-field">
                    <div class="agent-compare-label"><label class="af-field__label">对话模型</label><em v-if="comparisonChanged('modelId')" class="agent-compare-badge">存在差异</em></div>
                    <input class="af-input agent-compare-input" :class="{ 'agent-compare-input--changed': comparisonChanged('modelId') }" readonly :value="comparisonValue('modelId', side.key)" />
                  </div>
                  <div class="af-field">
                    <div class="agent-compare-label"><label class="af-field__label">记忆类型</label><em v-if="comparisonChanged('chatMemoryType')" class="agent-compare-badge">存在差异</em></div>
                    <input class="af-input agent-compare-input" :class="{ 'agent-compare-input--changed': comparisonChanged('chatMemoryType') }" readonly :value="comparisonValue('chatMemoryType', side.key)" />
                    <div class="agent-memory-config">
                      <div class="agent-compare-label"><label class="af-field__label">最大记忆消息数</label><em v-if="comparisonChanged('chatMemoryMaxMessages')" class="agent-compare-badge">存在差异</em></div>
                      <input class="af-input agent-compare-input" :class="{ 'agent-compare-input--changed': comparisonChanged('chatMemoryMaxMessages') }" readonly :value="comparisonValue('chatMemoryMaxMessages', side.key)" />
                      <div class="af-hint">限制单个调试会话保留的历史消息数量。</div>
                    </div>
                  </div>
                </div>
              </section>
            </article>
          </div>
        </div>
      </div>
    </div>
    <div v-if="mcpPickerVisible" class="af-modal">
      <div class="af-modal__backdrop" @click="mcpPickerVisible = false"></div>
      <div class="af-modal__panel agent-mcp-picker-panel" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">选择 MCP 插件</h2><button class="af-modal__close" @click="mcpPickerVisible = false">×</button></div>
        <div class="af-modal__body agent-version-body">
          <div class="agent-mcp-picker__toolbar">
            <div class="af-search">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" /></svg>
              <input v-model.trim="mcpKeyword" type="search" placeholder="搜索 MCP 插件名称" />
            </div>
            <span class="agent-mcp-picker__count">已选 {{ mcpDraft.length }} 个插件</span>
          </div>
          <div v-if="mcpLoading" class="af-loading"><span class="af-spinner"></span>正在加载 MCP 插件...</div>
          <div v-else-if="!mcpPlugins.length" class="agent-version-empty">暂无可用 MCP 插件，请先在“MCP 插件”页面创建。</div>
          <template v-else>
            <div v-if="!mcpPagedPlugins.length" class="agent-version-empty">未找到匹配的 MCP 插件</div>
            <div v-else class="agent-mcp-picker">
              <label v-for="plugin in mcpPagedPlugins" :key="plugin.id" class="agent-mcp-picker__plugin">
                <div class="agent-mcp-picker__head">
                  <input type="checkbox" :checked="mcpPluginChecked(plugin.id)" @change="toggleMcpPlugin(plugin.id, $event.target.checked)" />
                  <b>{{ plugin.name }}</b>
                  <span class="agent-mcp-picker__meta">{{ (plugin.servers || []).length }} 个 Server{{ plugin.status === 1 ? '' : ' · 已禁用' }}</span>
                </div>
                <p v-if="plugin.description" class="agent-mcp-picker__desc">{{ plugin.description }}</p>
                <ul v-if="mcpPluginChecked(plugin.id) && (plugin.servers || []).length" class="agent-mcp-picker__servers">
                  <li v-for="server in plugin.servers" :key="server.id">
                    <label>
                      <input type="checkbox" :checked="mcpServerChecked(plugin.id, server.id)" @change="toggleMcpServer(plugin.id, server.id, $event.target.checked)" />
                      <span>{{ server.serverName }}</span>
                      <small v-if="server.status !== 1">（已禁用）</small>
                    </label>
                  </li>
                </ul>
                <div v-else-if="mcpPluginChecked(plugin.id)" class="af-hint">该插件下暂无 Server，默认调用全部（后续新增自动生效）。</div>
              </label>
            </div>
            <div class="agent-mcp-picker__pager">
              <span>共 {{ mcpFilteredPlugins.length }} 个插件 · 第 {{ mcpPage }} / {{ mcpTotalPages }} 页</span>
              <div class="agent-mcp-picker__pager-ops">
                <select v-model.number="mcpPageSize" class="af-select agent-mcp-picker__size">
                  <option :value="5">5 条/页</option>
                  <option :value="8">8 条/页</option>
                  <option :value="20">20 条/页</option>
                </select>
                <button class="af-btn af-btn--sm" :disabled="mcpPage <= 1" @click="gotoMcpPage(mcpPage - 1)">上一页</button>
                <button class="af-btn af-btn--sm" :disabled="mcpPage >= mcpTotalPages" @click="gotoMcpPage(mcpPage + 1)">下一页</button>
              </div>
            </div>
          </template>
        </div>
        <div class="af-modal__foot agent-mcp-picker__foot">
          <button class="af-btn" @click="mcpPickerVisible = false">取消</button>
          <button class="af-btn af-btn--primary" @click="confirmMcpPicker">确定</button>
        </div>
      </div>
    </div>
    <div v-if="remarkModalVisible" class="af-modal">
      <div class="af-modal__backdrop" @click="closeRemarkModal"></div>
      <div class="af-modal__panel agent-version-remark-panel" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">{{ remarkModalHasRemark ? '编辑' : '添加' }} V{{ remarkModalVersionNo }} 版本备注</h2><button class="af-modal__close" @click="closeRemarkModal">×</button></div>
        <div class="af-modal__body agent-version-remark-body">
          <div class="af-version-remark-meta">版本名称：<b>{{ remarkModalVersionName || '—' }}</b></div>
          <div class="af-field">
            <label class="af-field__label">版本备注</label>
            <textarea v-model="remarkDraft" class="af-input agent-version-remark-input" rows="8" maxlength="1024" placeholder="记录这个版本的改动要点、适用范围或回滚说明等（最多 1024 个字符）"></textarea>
            <div class="af-hint agent-version-remark-counter">{{ remarkDraft.length }} / 1024</div>
          </div>
        </div>
        <div class="af-modal__footer agent-version-confirm-footer">
          <button class="af-btn" :disabled="remarkSaving" @click="closeRemarkModal">取消</button>
          <button class="af-btn af-btn--primary" :disabled="remarkSaving" @click="saveRemark">{{ remarkSaving ? '保存中...' : '保存备注' }}</button>
        </div>
      </div>
    </div>
    <div v-if="versionConfirmVisible" class="af-modal">
      <div class="af-modal__backdrop" @click="versionConfirmVisible = false"></div>
      <div class="af-modal__panel agent-version-confirm-panel" role="dialog" aria-modal="true">
        <div class="af-modal__head"><h2 class="af-modal__title">确认提交版本</h2><button class="af-modal__close" @click="versionConfirmVisible = false">×</button></div>
        <div class="af-modal__body agent-version-confirm-body">
          <p>将保存当前配置，并生成一条新的历史版本记录。已生成的版本不会被覆盖。</p>
          <p class="agent-version-confirm-hint">如果只需要保存编辑进度、不生成历史版本，请使用“仅保存”。</p>
          <div class="af-field agent-version-confirm-remark">
            <label class="af-field__label">版本备注（选填）</label>
            <textarea v-model.trim="versionRemarkDraft" class="af-input" rows="3" maxlength="1024" placeholder="补充本次提交的说明，例如：优化系统提示词、新增 MCP 工具"></textarea>
            <div class="af-hint">备注会在版本历史中展示，提交后仍可随时编辑。最多 1024 个字符。</div>
          </div>
        </div>
        <div class="af-modal__footer agent-version-confirm-footer">
          <button class="af-btn" :disabled="saving" @click="versionConfirmVisible = false">取消</button>
          <button class="af-btn af-btn--primary" :disabled="saving" @click="confirmSubmitVersion">{{ saving ? '提交中...' : '确认保存并提交版本' }}</button>
        </div>
      </div>
    </div>
    <div v-if="tip" class="af-toast af-toast--show" :class="{ 'af-toast--error': tipError }">{{ tip }}</div>
  </div>
</template>

<script>
import { fetchAgentVersions, fetchChatModels, getAgent, getAgentVersion, switchAgentVersion, updateAgentPublish, updateAgentVersionRemark, upsertAgent } from './api'
import { fetchMcpPlugins } from '../mcp/api'
import AgentDebugPanel from './AgentDebugPanel.vue'

export default {
  name: 'AgentConfigPage',
  components: { AgentDebugPanel },
  data() { return { loading: true, saving: false, publishing: false, historyVisible: false, versionLoading: false, switchingVersion: null, comparingVersion: null, compareVisible: false, comparedVersionNo: null, comparisonRows: [], versionConfirmVisible: false, versionRemarkDraft: '', remarkModalVisible: false, remarkModalVersionNo: null, remarkModalVersionName: '', remarkModalHasRemark: false, remarkDraft: '', remarkSaving: false, versions: [], memoryConfigExpanded: false, detail: {}, models: [], mcpPlugins: [], mcpLoading: false, mcpPickerVisible: false, mcpDraft: [], mcpKeyword: '', mcpPage: 1, mcpPageSize: 5, form: { id: null, name: '', description: '', modelId: null, systemPrompt: '', chatMemoryType: 1, chatMemoryMaxMessages: 1000, mcpPlugins: [] }, baseline: '', tip: '', tipError: false, tipTimer: null } },
  computed: {
    isPublished() { return Number(this.detail.publish) === 1 },
    isDirty() { return Boolean(this.baseline) && JSON.stringify(this.form) !== this.baseline },
    publishedChatUrl() { return '/agent/published/' + encodeURIComponent(this.detail.aid || '') },
    lastPublishTimeText() { return this.formatTime(this.detail.lastPublishTime) },
    mcpFilteredPlugins() { const kw = this.mcpKeyword.trim().toLowerCase(); if (!kw) return this.mcpPlugins; return this.mcpPlugins.filter((item) => (item.name || '').toLowerCase().includes(kw) || (item.description || '').toLowerCase().includes(kw)) },
    mcpTotalPages() { return Math.max(1, Math.ceil(this.mcpFilteredPlugins.length / this.mcpPageSize)) },
    mcpPagedPlugins() { const start = (this.mcpPage - 1) * this.mcpPageSize; return this.mcpFilteredPlugins.slice(start, start + this.mcpPageSize) },
    compareSides() { return [{ key: 'version', title: 'V' + this.comparedVersionNo + ' 历史版本', hint: '历史配置快照' }, { key: 'current', title: '当前最新配置', hint: '当前编辑态已保存配置' }] },
    comparisonChangedCount() { return this.comparisonRows.filter((row) => row.changed).length }
  },
  watch: {
    mcpKeyword() { this.mcpPage = 1 },
    mcpPageSize() { this.mcpPage = 1 }
  },
  async created() {
    const id = new URLSearchParams(window.location.search).get('id')
    if (!id) { window.location.href = '/agents'; return }
    try { const [detail, modelResult] = await Promise.all([getAgent(id), fetchChatModels()]); this.applyDetail(detail); this.models = modelResult.data || []; this.mcpPlugins = (await fetchMcpPlugins('', true)) || [] } catch (e) { this.toast('加载 Agent 配置失败：' + e.message, true) } finally { this.loading = false }
  },
  beforeDestroy() { clearTimeout(this.tipTimer) },
  methods: {
    applyDetail(detail) { this.detail = detail; this.form = { id: detail.id, name: detail.name, description: detail.description || '', modelId: detail.modelId, systemPrompt: detail.systemPrompt || '', chatMemoryType: detail.chatMemoryType || 1, chatMemoryMaxMessages: detail.chatMemoryMaxMessages || 1000, mcpPlugins: (detail.mcpPlugins || []).map((item) => ({ pluginId: item.pluginId, serverIds: item.serverIds || [] })) }; this.baseline = JSON.stringify(this.form) },
    // ---------- MCP 选择 ----------
    async openMcpPicker() {
      this.mcpPickerVisible = true
      this.mcpKeyword = ''
      this.mcpPage = 1
      this.mcpLoading = true
      try { this.mcpPlugins = (await fetchMcpPlugins('', true)) || [] } catch (e) { this.toast('加载 MCP 插件失败：' + e.message, true) } finally { this.mcpLoading = false }
      this.mcpDraft = this.form.mcpPlugins.map((item) => ({ pluginId: item.pluginId, serverIds: [...(item.serverIds || [])] }))
    },
    mcpPluginChecked(pluginId) { return this.mcpDraft.some((item) => Number(item.pluginId) === Number(pluginId)) },
    mcpServerChecked(pluginId, serverId) { const item = this.mcpDraft.find((entry) => Number(entry.pluginId) === Number(pluginId)); return Boolean(item && item.serverIds.some((id) => Number(id) === Number(serverId))) },
    toggleMcpPlugin(pluginId, checked) {
      if (checked) { this.mcpDraft.push({ pluginId, serverIds: [] }) }
      else { this.mcpDraft = this.mcpDraft.filter((item) => Number(item.pluginId) !== Number(pluginId)) }
    },
    toggleMcpServer(pluginId, serverId, checked) {
      const item = this.mcpDraft.find((entry) => Number(entry.pluginId) === Number(pluginId))
      if (!item) return
      if (checked) { if (!item.serverIds.some((id) => Number(id) === Number(serverId))) item.serverIds.push(serverId) }
      else { item.serverIds = item.serverIds.filter((id) => Number(id) !== Number(serverId)) }
    },
    gotoMcpPage(page) { if (page < 1 || page > this.mcpTotalPages) return; this.mcpPage = page },
    confirmMcpPicker() { this.form.mcpPlugins = this.mcpDraft.map((item) => ({ pluginId: item.pluginId, serverIds: [...item.serverIds] })); this.mcpPickerVisible = false },
    mcpPluginName(pluginId) { const plugin = this.mcpPlugins.find((item) => Number(item.id) === Number(pluginId)); return plugin ? plugin.name : '插件 #' + pluginId },
    removeMcpPlugin(pluginId) { this.form.mcpPlugins = this.form.mcpPlugins.filter((item) => Number(item.pluginId) !== Number(pluginId)) },
    async save(submitVersion) { if (!this.form.name || !this.form.modelId || !this.form.systemPrompt) { this.toast('请完整填写 Agent 配置', true); return false } this.saving = true; try { const detail = await upsertAgent({ ...this.form, submitVersion, versionRemark: submitVersion ? this.versionRemarkDraft : '' }); this.applyDetail(detail); this.toast(submitVersion ? 'Agent 配置已保存并提交 V' + detail.submittedVersionNo : 'Agent 配置已保存'); if (this.historyVisible) await this.loadVersions(); return true } catch (e) { this.toast('保存失败：' + e.message, true); return false } finally { this.saving = false } },
    openVersionConfirm() { this.versionRemarkDraft = ''; this.versionConfirmVisible = true },
    async confirmSubmitVersion() { this.versionConfirmVisible = false; const saved = await this.save(true); if (saved) this.versionRemarkDraft = '' },
    // ---------- 版本备注 ----------
    openRemarkModal(version) { this.remarkModalVersionNo = version.versionNo; this.remarkModalVersionName = version.name || ''; this.remarkModalHasRemark = Boolean(version.versionRemark); this.remarkDraft = version.versionRemark || ''; this.remarkModalVisible = true },
    closeRemarkModal() { if (this.remarkSaving) return; this.remarkModalVisible = false; this.remarkModalVersionNo = null; this.remarkModalVersionName = ''; this.remarkModalHasRemark = false; this.remarkDraft = '' },
    async saveRemark() {
      const version = this.versions.find((item) => Number(item.versionNo) === Number(this.remarkModalVersionNo))
      if (!version) return this.closeRemarkModal()
      const versionRemark = (this.remarkDraft || '').trim()
      if (versionRemark.length > 1024) return this.toast('版本备注不能超过 1024 个字符', true)
      this.remarkSaving = true
      try {
        const updated = await updateAgentVersionRemark(this.detail.id, version.versionNo, versionRemark)
        version.versionRemark = updated && updated.versionRemark != null ? updated.versionRemark : versionRemark
        this.toast('V' + version.versionNo + (versionRemark ? ' 备注已更新' : ' 备注已清空'))
        this.remarkModalVisible = false
        this.remarkModalVersionNo = null
        this.remarkModalVersionName = ''
        this.remarkModalHasRemark = false
        this.remarkDraft = ''
      } catch (e) { this.toast('更新版本备注失败：' + e.message, true) } finally { this.remarkSaving = false }
    },
    async publish() { if (this.publishing) return; const republish = this.isPublished; if (republish && this.isDirty && !window.confirm('当前有未保存的修改，重新发布只会同步“已保存”的最新配置。是否继续？')) return; this.publishing = 'publish'; try { this.detail = await updateAgentPublish(this.detail.id, true); this.toast(republish ? 'Agent 已重新发布（已同步最新保存的配置）' : 'Agent 已发布') } catch (e) { this.toast((republish ? '重新发布' : '发布') + '失败：' + e.message, true) } finally { this.publishing = '' } },
    async unpublish() { if (this.publishing) return; this.publishing = 'unpublish'; try { this.detail = await updateAgentPublish(this.detail.id, false); this.toast('Agent 已停止发布') } catch (e) { this.toast('停止发布失败：' + e.message, true) } finally { this.publishing = '' } },
    async openHistory() { this.historyVisible = true; await this.loadVersions() },
    closeHistory() { if (this.switchingVersion === null) this.historyVisible = false },
    async loadVersions() { this.versionLoading = true; try { this.versions = await fetchAgentVersions(this.detail.id) || [] } catch (e) { this.toast('加载版本历史失败：' + e.message, true) } finally { this.versionLoading = false } },
    async switchToVersion(version) { if (!window.confirm('切换后将把 V' + version.versionNo + ' 的配置回填到编辑页，仅修改页面内容，不会保存。是否继续？')) return; this.switchingVersion = version.versionNo; try { const snapshot = await switchAgentVersion(this.detail.id, version.versionNo); this.applyVersionToForm(snapshot); this.historyVisible = false; this.toast('已载入 V' + version.versionNo + ' 配置，尚未保存') } catch (e) { this.toast('切换版本失败：' + e.message, true) } finally { this.switchingVersion = null } },
    applyVersionToForm(version) { const config = this.parseAgentConfig(version.agentConfig); this.form = { ...this.form, name: version.name, description: version.description || '', modelId: config.modelId, systemPrompt: config.systemPrompt, chatMemoryType: config.chatMemoryType, chatMemoryMaxMessages: config.chatMemoryMaxMessages } },
    async compareWithCurrent(version) { this.comparingVersion = version.versionNo; try { const [historical, current] = await Promise.all([getAgentVersion(this.detail.id, version.versionNo), getAgent(this.detail.id)]); this.comparedVersionNo = version.versionNo; this.comparisonRows = this.buildComparisonRows(historical, current); this.compareVisible = true } catch (e) { this.toast('加载版本对比失败：' + e.message, true) } finally { this.comparingVersion = null } },
    closeCompare() { this.compareVisible = false; this.comparedVersionNo = null; this.comparisonRows = [] },
    comparisonValue(key, side) { const row = this.comparisonRows.find((item) => item.key === key); return row ? row[side + 'Value'] : '—' },
    comparisonChanged(key) { const row = this.comparisonRows.find((item) => item.key === key); return Boolean(row && row.changed) },
    buildComparisonRows(historical, current) { const versionConfig = this.parseAgentConfig(historical.agentConfig); const currentConfig = { systemPrompt: current.systemPrompt || '', modelId: current.modelId, chatMemoryType: current.chatMemoryType || 1, chatMemoryMaxMessages: current.chatMemoryMaxMessages || 1000 }; const fields = [{ key: 'name', label: 'Agent 名称', versionValue: historical.name || '', currentValue: current.name || '' }, { key: 'description', label: 'Agent 描述', versionValue: historical.description || '', currentValue: current.description || '' }, { key: 'systemPrompt', label: 'Prompt 提示词', versionValue: versionConfig.systemPrompt, currentValue: currentConfig.systemPrompt }, { key: 'modelId', label: '对话模型', versionValue: this.modelLabel(versionConfig.modelId), currentValue: this.modelLabel(currentConfig.modelId) }, { key: 'chatMemoryType', label: '记忆类型', versionValue: this.memoryTypeLabel(versionConfig.chatMemoryType), currentValue: this.memoryTypeLabel(currentConfig.chatMemoryType) }, { key: 'chatMemoryMaxMessages', label: '最大记忆消息数', versionValue: String(versionConfig.chatMemoryMaxMessages), currentValue: String(currentConfig.chatMemoryMaxMessages) }]; return fields.map((field) => ({ ...field, versionValue: field.versionValue || '—', currentValue: field.currentValue || '—', changed: String(field.versionValue || '') !== String(field.currentValue || '') })) },
    parseAgentConfig(raw) { try { const config = JSON.parse(raw || '{}'); const chatModel = config.chatModel || {}; const memory = config.chatMemoryConfig || {}; let memoryConfig = {}; try { memoryConfig = JSON.parse(memory.memoryConfig || '{}') } catch (_e) { memoryConfig = {} } return { systemPrompt: config.systemPrompt || '', modelId: chatModel.llm_model_id ?? chatModel.llmModelId ?? null, chatMemoryType: memory.type || 1, chatMemoryMaxMessages: memoryConfig.maxMessage || 1000 } } catch (_e) { return { systemPrompt: '', modelId: null, chatMemoryType: 1, chatMemoryMaxMessages: 1000 } } },
    modelLabel(modelId) { const model = this.models.find((item) => Number(item.id) === Number(modelId)); return model ? model.name + '（' + model.modelname + '）' : (modelId == null ? '—' : '模型 ID: ' + modelId) },
    memoryTypeLabel(type) { return Number(type) === 1 ? '窗口会话记忆' : '记忆类型 ' + type },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' },
    toast(text, error = false) { this.tip = text; this.tipError = error; clearTimeout(this.tipTimer); this.tipTimer = setTimeout(() => { this.tip = '' }, 2800) }
  }
}
</script>

<style lang="scss">
@use '../models/styles/tokens' as *;
.agent-config-page { min-height: 100vh; background: #f5f7fa; color: $color-text; }
.agent-config-head { height: 64px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 20px; padding: 0 24px; border-bottom: 1px solid $color-border; background: #fff; }
.agent-config-head-actions { display: flex; justify-self: end; align-items: center; gap: 8px; }
.agent-save-actions { display: flex; align-items: stretch; .af-btn:first-child { border-radius: 6px 0 0 6px; } .af-btn:last-child { margin-left: -1px; border-radius: 0 6px 6px 0; } }
.agent-save-version-btn { border-color: #6b9fe8; background: #f5f9ff; color: #286bc5; }
.agent-visit-btn { display: inline-flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 1px; border-color: #b9d2f5; background: #f4f8ff; color: #286bc5; line-height: 1.15; text-decoration: none; &:hover { border-color: #78a8ea; background: #eaf3ff; } }
.agent-visit-btn__label { font-size: 12.5px; font-weight: 600; }
.agent-visit-btn__time { color: #7d9dc9; font-size: 10.5px; font-weight: 400; }
.agent-publish-btn { border-color: #8bb6f3; background: #eef5ff; color: #286bc5; &:hover { border-color: #5d98e8; background: #e3efff; } &--active { border-color: #efb0ac; background: #fff2f1; color: #c9423a; &:hover { border-color: #e17e77; background: #ffe8e6; } } }
.agent-publish-live-dot { position: relative; width: 7px; height: 7px; flex: 0 0 7px; margin-right: 1px; border-radius: 50%; background: #24b36b; box-shadow: 0 0 0 3px rgba(36, 179, 107, .14), 0 0 8px rgba(36, 179, 107, .65); animation: agent-publish-pulse 1.8s ease-in-out infinite; }
@keyframes agent-publish-pulse { 0%, 100% { opacity: 1; box-shadow: 0 0 0 3px rgba(36, 179, 107, .13), 0 0 7px rgba(36, 179, 107, .55); } 50% { opacity: .72; box-shadow: 0 0 0 5px rgba(36, 179, 107, .05), 0 0 11px rgba(36, 179, 107, .8); } }
.agent-config-back { color: $color-text-secondary; text-decoration: none; font-size: 13px; }
.agent-config-title { display: flex; align-items: center; gap: 2px; white-space: nowrap; &__label { color: $color-text-secondary; font-size: 15px; font-weight: 600; } strong { max-width: 260px; overflow: hidden; font-size: 17px; text-overflow: ellipsis; } }
.agent-config-layout { height: calc(100vh - 64px); display: grid; grid-template-columns: minmax(260px, .78fr) minmax(300px, .9fr) minmax(400px, 1.32fr); gap: 12px; padding: 12px; }
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
.agent-version-panel { width: min(560px, calc(100vw - 32px)); }
.af-modal__panel.agent-version-confirm-panel { width: min(460px, calc(100vw - 32px)); }
.agent-version-confirm-footer { justify-content: space-between; }
.agent-version-confirm-body { gap: 10px; p { margin: 0; color: $color-text-secondary; font-size: 13px; line-height: 1.7; } .agent-version-confirm-hint { color: $color-text-muted; font-size: 12px; } }
.agent-version-body { max-height: 520px; overflow-y: auto; }
.agent-version-confirm-remark { gap: 8px; margin-top: 4px; }
.agent-version-tip { display: flex; align-items: flex-start; gap: 9px; padding: 11px 13px; border: 1px solid #d6e4f7; border-radius: 9px; background: #f3f8ff; b { display: block; color: #2f5d99; font-size: 12.5px; } p { margin: 4px 0 0; color: #5b7697; font-size: 11.5px; line-height: 1.65; } }
.agent-version-tip__icon { display: inline-flex; width: 17px; height: 17px; flex: 0 0 17px; align-items: center; justify-content: center; margin-top: 1px; border-radius: 50%; background: #6b9fe8; color: #fff; font-size: 11px; font-style: normal; font-weight: 700; }
.agent-version-empty { padding: 32px 12px; color: $color-text-muted; text-align: center; }
.agent-version-list { display: flex; flex-direction: column; gap: 10px; }
.agent-version-item { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px; border: 1px solid $color-border; border-radius: 9px; background: #fff; b { color: $color-brand; font-size: 15px; } small { grid-column: 1 / -1; overflow: hidden; color: $color-text-secondary; text-overflow: ellipsis; white-space: nowrap; } }
.agent-version-info { display: grid; min-width: 0; flex: 1; grid-template-columns: auto auto; align-items: baseline; gap: 4px 12px; > span { color: $color-text-muted; font-size: 12px; } }
.agent-version-remark { display: flex; grid-column: 1 / -1; align-items: baseline; gap: 6px; margin: 2px 0 0; color: $color-text-secondary; font-size: 12px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.agent-version-remark__label { flex-shrink: 0; padding: 0 6px; border-radius: 7px; background: $color-sidebar; color: $color-text-muted; font-size: 11px; }
.agent-version-remark__empty { color: $color-text-faint; }
.af-modal__panel.agent-version-remark-panel { width: min(560px, calc(100vw - 32px)); }
.agent-version-remark-body { gap: 14px; }
.af-version-remark-meta { color: $color-text-muted; font-size: 12px; b { color: $color-text-secondary; } }
.af-input.agent-version-remark-input { min-height: 190px; padding: 10px 12px; resize: vertical; font-size: 13px; line-height: 1.7; }
.agent-version-remark-counter { align-self: flex-end; }
.agent-version-item .agent-version-actions { display: flex; flex-shrink: 0; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.agent-version-compare-btn { border-color: #9ab8df; background: #f5f9ff; color: #315f99; }
.agent-compare-modal { z-index: 1200; }
.af-modal__panel.agent-compare-panel { width: min(1440px, calc(100vw - 96px)); max-width: 1440px; height: min(840px, calc(100vh - 96px)); max-height: calc(100vh - 96px); }
.agent-compare-body { flex: 1; max-height: none; overflow: auto; padding: 14px 16px 18px; background: #f5f7fa; }
.agent-compare-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; padding: 0 2px; }
.agent-compare-legend { display: inline-flex; align-items: center; gap: 7px; color: $color-text-secondary; font-size: 12px; i { width: 14px; height: 14px; border: 2px solid #e45b52; border-radius: 4px; background: #fff8f7; } }
.agent-compare-summary { padding: 3px 10px; border-radius: 10px; background: #fff0ef; color: #c9423a; font-size: 12px; &--same { background: #eaf7f0; color: #1d8a55; } }
.agent-compare-columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; align-items: stretch; min-height: 100%; }
.agent-compare-column { display: flex; min-width: 0; flex-direction: column; gap: 12px; }
.agent-compare-column-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding: 0 2px; b { color: $color-text; font-size: 15px; } span { color: $color-text-muted; font-size: 11px; } }
.agent-compare-prompt { height: 340px; flex: 0 0 340px; &--changed { border: 2px solid #e45b52; background: #fffafa; box-shadow: 0 0 0 3px rgba(228, 91, 82, .08); } .agent-config-panel__head { padding-right: 12px; } }
.agent-compare-prompt-text { flex: 1; min-height: 0; margin: 0; overflow: auto; padding: 18px; color: $color-text; font: 13px/1.75 $font-mono; white-space: pre-wrap; word-break: break-word; }
.agent-compare-details { flex: 1; }
.agent-compare-details .agent-config-fields { padding: 18px; }
.agent-compare-label { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 18px; }
.agent-compare-input { cursor: default; }
.af-input.agent-compare-input--changed { border: 2px solid #e45b52; background: #fff8f7; box-shadow: 0 0 0 2px rgba(228, 91, 82, .07); }
textarea.agent-compare-input { resize: none; }
.agent-compare-details .agent-memory-config { margin-top: 10px; }
.agent-compare-details .agent-memory-config .agent-compare-label { margin-bottom: 8px; }
.agent-compare-badge { padding: 1px 7px; border-radius: 10px; background: #fff0ef; color: #d33f36; font-size: 11px; font-style: normal; white-space: nowrap; }
.agent-compare-prompt .agent-compare-badge { flex-shrink: 0; }
@media (max-width: 900px) { .af-modal__panel.agent-compare-panel { width: calc(100vw - 16px); max-width: calc(100vw - 16px); height: calc(100vh - 16px); max-height: calc(100vh - 16px); } .agent-compare-columns { grid-template-columns: 1fr; } .agent-compare-prompt { height: 260px; flex-basis: 260px; } }
.agent-debug-head { gap: 10px; }
.agent-debug-head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.agent-debug-mode { width: 172px; }
.agent-debug-clear-btn { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 28px; padding: 0; border: 1px solid #cbd8e9; border-radius: 5px; background: #f6f9fd; color: #657791; cursor: pointer; box-shadow: 0 1px 2px rgba(59, 111, 182, .06); transition: all .15s; svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round; } &:hover { border-color: #8eb3e5; background: #edf4fd; color: #3b6fb6; box-shadow: 0 2px 6px rgba(59, 111, 182, .14); transform: translateY(-1px); } &:active { transform: translateY(0); box-shadow: none; } }
.agent-debug-feed { flex: 1; overflow-y: auto; padding: 16px 32px; }
.agent-debug-empty { height: 100%; display: flex; align-items: center; justify-content: center; flex-direction: column; text-align: center; color: $color-text-muted; span { margin-bottom: 12px; color: $color-brand; font-size: 30px; } b { color: $color-text-secondary; font-size: 13px; } p { max-width: 240px; font-size: 11.5px; line-height: 1.6; } }
.agent-debug-message-row { display: flex; width: 100%; margin-bottom: 20px; font-size: 13px; &--user { justify-content: flex-end; } &--assistant { display: grid; grid-template-columns: minmax(0, 1fr); gap: 8px; justify-items: stretch; } }
.agent-debug-user-block { display: flex; max-width: 78%; flex-direction: column; align-items: flex-end; gap: 4px; }
.agent-debug-user-bubble { max-width: 100%; padding: 10px 15px; border: 1px solid rgba(122, 166, 225, .28); border-radius: 16px 16px 5px 16px; background: linear-gradient(145deg, #f5f9ff 0%, #e9f2ff 100%); box-shadow: 0 1px 2px rgba(44, 83, 130, .05), 0 5px 16px rgba(74, 126, 194, .08); color: #2f527b; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.agent-debug-bot-identity { display: flex; width: 100%; min-width: 0; align-items: center; gap: 9px; margin: 0; padding: 0; }
.agent-debug-bot-avatar { display: flex; width: 32px; height: 32px; flex-shrink: 0; align-items: center; justify-content: center; overflow: hidden; border-radius: 9px; background: #f4f8ff; img { display: block; width: 100%; height: 100%; object-fit: cover; } }
.agent-debug-bot-name { min-width: 0; overflow: hidden; color: #344054; font-size: 13px; font-weight: 600; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.agent-debug-bot-body { width: 100%; min-width: 0; margin: 0; padding: 0; }
.agent-debug-bot-body .agent-message-content, .agent-debug-bot-body .agent-message, .agent-debug-bot-body .answer, .agent-debug-bot-body .af-markdown { margin-left: 0; padding-left: 0; }
.agent-debug-message-actions { display: flex; align-items: center; gap: 6px; }
.agent-debug-mini-btn { padding: 4px 6px; border: 0; border-radius: 6px; background: transparent; color: #98a2b3; cursor: pointer; font-size: 14px; line-height: 1; transition: all .15s; &:hover { background: #f0f3f8; color: #3b6fb6; } &:disabled { cursor: not-allowed; opacity: .4; } }
.agent-debug-status-error { padding: 1px 6px; border-radius: 4px; background: #fff0f0; color: #d64545; font-size: 11px; }
.agent-debug-input { display: flex; align-items: center; gap: 10px; margin: 0 28px 12px; padding: 8px; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: 0 4px 16px rgba(31, 56, 88, .07); textarea { flex: 1; height: 54px; resize: none; border: 0; border-radius: 8px; padding: 10px 8px; outline: 0; background: transparent; color: $color-text; font: inherit; font-size: 13px; line-height: 1.55; } .af-btn { min-width: 68px; height: 36px; align-self: center; border-radius: 9px; } }
.af-agent-card { cursor: pointer; }
.agent-mcp-pick-btn { border-color: #b9d2f5; background: #f4f8ff; color: #286bc5; }
.agent-mcp-empty { padding: 10px 12px; border-radius: 8px; background: $color-sidebar; color: $color-text-muted; font-size: 12px; }
.agent-mcp-list { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.agent-mcp-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid $color-border; border-radius: 8px; background: $color-sidebar; font-size: 12.5px; b { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } span { color: $color-text-muted; flex-shrink: 0; } }
.af-modal__panel.agent-mcp-picker-panel { width: min(620px, calc(100vw - 32px)); }
.agent-mcp-picker__toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; .af-search { flex: 1; min-width: 180px; max-width: none; } }
.agent-mcp-picker__count { flex-shrink: 0; color: $color-text-muted; font-size: 12px; }
.agent-mcp-picker { display: flex; flex-direction: column; gap: 10px; }
.agent-mcp-picker__meta { margin-left: auto; color: $color-text-muted; font-size: 11.5px; }
.agent-mcp-picker__desc { margin: 6px 0 0 24px; color: $color-text-muted; font-size: 11.5px; line-height: 1.55; }
.agent-mcp-picker__pager { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 12px; color: $color-text-muted; font-size: 12px; }
.agent-mcp-picker__pager-ops { display: flex; align-items: center; gap: 8px; }
.agent-mcp-picker__size { width: 104px; height: 28px; font-size: 12px; }
.agent-mcp-picker__plugin { display: block; padding: 12px; border: 1px solid $color-border; border-radius: 9px; background: #fff; }
.agent-mcp-picker__head { display: flex; align-items: center; gap: 8px; b { font-size: 13px; } span { color: $color-text-muted; font-size: 11.5px; } }
.agent-mcp-picker__servers { margin: 10px 0 0; padding: 0 0 0 6px; list-style: none; display: flex; flex-direction: column; gap: 6px; label { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: $color-text-secondary; cursor: pointer; } small { color: $color-text-muted; } }
.agent-mcp-picker__foot { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid $color-border; }
.af-mini-btn { padding: 3px 8px; border: 1px solid transparent; border-radius: 5px; background: transparent; color: #657791; cursor: pointer; font-size: 11.5px; &:hover { border-color: #f0c4c0; background: #fff3f2; color: #c9423a; } &--danger:hover { border-color: #f0c4c0; background: #fff3f2; color: #c9423a; } }
@media (max-width: 1100px) { .agent-config-layout { height: auto; grid-template-columns: 1fr 1fr; } .agent-debug { min-height: 520px; grid-column: 1 / -1; } }
@media (max-width: 720px) { .agent-config-head { grid-template-columns: 1fr auto; } .agent-config-title { display: none; } .agent-config-layout { grid-template-columns: 1fr; } .agent-config-panel { min-height: 480px; } .agent-debug { grid-column: auto; } }
</style>
