<template>
  <div class="agent-config-page">
    <header class="agent-config-head">
      <div class="agent-config-head__main">
        <el-button type="text" icon="el-icon-back" class="agent-config-back" @click="backToList">返回 Agent 列表</el-button>
        <div class="agent-config-title">
          <strong v-if="!loading">{{ form.name || '未命名 Agent' }}</strong>
          <strong v-else class="agent-config-title__loading">正在载入配置…</strong>
        </div>
        <template v-if="!loading">
          <el-tag v-if="isPublished" size="mini" type="success" effect="plain">已发布</el-tag>
          <el-tag v-else size="mini" type="info" effect="plain">未发布</el-tag>
          <span class="agent-config-title__aid">AID {{ detail.aid || '—' }}</span>
        </template>
      </div>

      <div class="agent-config-head-actions">
        <a
          v-if="isPublished"
          class="agent-config-visit"
          :href="publishedChatUrl"
          target="_blank"
          rel="noopener"
          :title="'最新发布 ' + lastPublishTimeText"
        >
          <i class="el-icon-link"></i>访问发布页面
        </a>

        <el-button
          v-if="isPublished"
          size="small"
          icon="el-icon-refresh"
          :loading="publishing === 'publish'"
          :disabled="publishing || saving || loading"
          @click="publish"
        >重新发布</el-button>
        <el-button
          v-if="isPublished"
          size="small"
          type="danger"
          plain
          icon="el-icon-switch-button"
          :loading="publishing === 'unpublish'"
          :disabled="publishing || saving || loading"
          @click="unpublish"
        >停止发布</el-button>
        <el-button
          v-else
          size="small"
          icon="el-icon-upload2"
          :loading="publishing === 'publish'"
          :disabled="publishing || saving || loading"
          @click="publish"
        >发布</el-button>

        <span class="agent-head-divider" aria-hidden="true"></span>

        <el-button-group>
          <el-button size="small" type="primary" :loading="saving" :disabled="saving || publishing || loading" @click="save(false)">
            仅保存
          </el-button>
          <el-button size="small" :disabled="saving || publishing || loading" @click="openVersionConfirm">
            保存并提交版本
          </el-button>
        </el-button-group>

        <el-tooltip content="版本历史" placement="bottom-end">
          <el-button
            size="small"
            icon="el-icon-time"
            aria-label="版本历史"
            :disabled="saving || publishing || loading"
            @click="openHistory"
          >版本历史</el-button>
        </el-tooltip>
      </div>
    </header>

    <main class="agent-config-layout" :class="{ 'agent-config-layout--loading': loading }">
      <section class="agent-config-panel agent-config-prompt">
        <div class="agent-config-panel__head">
          <div><b>Prompt 提示词</b><p>定义 Agent 的角色、目标和回答规则</p></div>
        </div>
        <div v-if="loading" class="agent-skeleton agent-skeleton--prompt">
          <span v-for="n in 9" :key="n" :style="{ width: 58 + ((n * 13) % 38) + '%' }"></span>
        </div>
        <textarea v-else v-model="form.systemPrompt" placeholder="请输入系统提示词..."></textarea>
      </section>

      <section class="agent-config-panel agent-config-details">
        <div class="agent-config-panel__head">
          <div><b>详细配置</b><p>配置 Agent 的模型与记忆策略</p></div>
        </div>
        <div class="agent-config-fields">
          <template v-if="loading">
            <div class="agent-skeleton agent-skeleton--summary">
              <span></span><span></span>
            </div>
            <div v-for="n in 4" :key="n" class="agent-skeleton-row"></div>
          </template>
          <template v-else>
            <div class="agent-config-overview">
            <div class="agent-config-summary"><span>Agent ID</span><code>{{ detail.aid }}</code></div>
            <div class="agent-config-summary"><span>Agent 类型</span><b>Standard ReAct Agent</b></div>
          </div>

          <el-form :model="form" label-width="112px" class="agent-config-form" @submit.native.prevent>
            <!-- Agent 描述统一在「Agent 列表」卡片编辑弹窗维护，这里不再重复 -->
            <el-form-item label="Agent 名称">
              <el-input v-model.trim="form.name" placeholder="请输入 Agent 名称" />
            </el-form-item>

            <el-form-item label="对话模型">
              <el-select v-model.number="form.modelId" placeholder="请选择对话模型" style="width: 100%">
                <el-option
                  v-for="model in models"
                  :key="model.id"
                  :label="model.name + '（' + model.modelname + '）'"
                  :value="model.id"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="MCP 插件">
              <div class="agent-mcp-toolbar">
                <el-button size="mini" icon="el-icon-folder-add" @click="openMcpPicker">选择 MCP 插件</el-button>
                <span class="af-form-tip">已绑定 {{ form.mcpPlugins.length }} 个插件</span>
              </div>
              <div v-if="!form.mcpPlugins.length" class="agent-mcp-empty">未绑定 MCP 插件，Agent 仅使用内置工具。</div>
              <el-collapse v-else v-model="expandedMcpPlugins" class="agent-mcp-collapse">
                <el-collapse-item v-for="item in form.mcpPlugins" :key="item.pluginId" :name="String(item.pluginId)">
                  <template slot="title">
                    <div class="agent-mcp-item__title">
                      <span class="agent-mcp-icon agent-mcp-icon--compact">
                        <span>{{ mcpPluginInitial(item.pluginId) }}</span>
                        <img v-if="mcpPluginIcon(item.pluginId)" :src="mcpPluginIcon(item.pluginId)" alt="" />
                      </span>
                      <b>{{ mcpPluginName(item.pluginId) }}</b>
                      <el-tag size="mini" effect="plain">
                        {{ (item.serverIds || []).length ? (item.serverIds || []).length + ' 个服务' : '全部服务' }}
                      </el-tag>
                      <el-button type="text" class="af-text-danger agent-mcp-item__remove" @click.stop="removeMcpPlugin(item.pluginId)">移除</el-button>
                    </div>
                  </template>
                  <ul class="agent-mcp-servers">
                    <li v-for="server in boundMcpServers(item)" :key="server.id">
                      <i class="el-icon-connection"></i>
                      <span>{{ server.serverName }}</span>
                      <el-tag v-if="server.status !== 1" size="mini" type="info">已禁用</el-tag>
                    </li>
                    <li v-if="!boundMcpServers(item).length" class="agent-mcp-servers__empty">
                      未指定具体服务，默认调用该插件下全部 Server（后续新增自动生效）。
                    </li>
                  </ul>
                </el-collapse-item>
              </el-collapse>
            </el-form-item>

            <el-form-item label="记忆类型">
              <el-select v-model.number="form.chatMemoryType" style="width: 100%">
                <el-option :value="1" label="窗口会话记忆" />
              </el-select>
              <div v-if="form.chatMemoryType === 1" class="agent-memory-settings">
                <button
                  type="button"
                  class="agent-memory-settings__head"
                  :aria-expanded="memorySettingsExpanded ? 'true' : 'false'"
                  @click="memorySettingsExpanded = !memorySettingsExpanded"
                >
                  <span class="agent-memory-settings__icon"><i class="el-icon-chat-line-square"></i></span>
                  <div>
                    <b>窗口记忆参数</b>
                  </div>
                  <i class="el-icon-arrow-down agent-memory-settings__arrow" :class="{ 'is-expanded': memorySettingsExpanded }"></i>
                </button>
                <div v-show="memorySettingsExpanded" class="agent-memory-settings__field">
                  <div class="agent-memory-settings__label">
                    <span>最大记忆消息数</span>
                    <small>限制单个调试会话保留的历史消息数量</small>
                  </div>
                  <el-input-number v-model="form.chatMemoryMaxMessages" :min="1" :max="10000" :step="100" controls-position="right" />
                </div>
              </div>
            </el-form-item>
          </el-form>
          </template>
        </div>
      </section>

      <AgentDebugPanel
        v-if="!loading"
        :aid="detail.aid"
        :agent-name="form.name"
        :agent-avatar="detail.avatar || '/logo.png'"
      />
      <aside v-else class="agent-config-panel agent-debug agent-debug-skeleton">
        <div class="agent-config-panel__head">
          <div><b>调试专区</b><p>使用当前 Agent 配置进行对话验证</p></div>
        </div>
        <div class="agent-skeleton agent-skeleton--center"><span></span><span></span></div>
      </aside>
    </main>

    <!-- ------------------------------ 版本历史 ------------------------------ -->
    <el-dialog :visible.sync="historyVisible" title="版本历史" width="720px" :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="切换版本只回填表单，不会保存"
        description="切换只会把该版本配置回填到编辑页，不会更新已保存的 Agent 配置。确认要使用该版本时，请点击右上角「仅保存」或「保存并提交版本」。"
      />
      <el-table v-loading="versionLoading" :data="versions" row-key="id" class="agent-version-table" empty-text="暂无历史版本，请先使用「保存并提交版本」">
        <el-table-column label="版本" width="76">
          <template slot-scope="{ row }"><b class="agent-version-no">V{{ row.versionNo }}</b></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="创建时间" width="170">
          <template slot-scope="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="180" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <span v-if="row.versionRemark">{{ row.versionRemark }}</span>
            <span v-else class="af-cell__sub">暂无备注</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template slot-scope="{ row }">
            <el-button type="text" :disabled="switchingVersion !== null || comparingVersion !== null" @click="openRemarkModal(row)">
              {{ row.versionRemark ? '编辑备注' : '添加备注' }}
            </el-button>
            <el-button type="text" :loading="switchingVersion === row.versionNo" :disabled="switchingVersion !== null || comparingVersion !== null" @click="switchToVersion(row)">
              切换版本
            </el-button>
            <el-button type="text" :loading="comparingVersion === row.versionNo" :disabled="switchingVersion !== null || comparingVersion !== null" @click="compareWithCurrent(row)">
              对比版本
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- ------------------------------ 版本对比 ------------------------------ -->
    <el-dialog :visible.sync="compareVisible" :title="'V' + comparedVersionNo + ' 与当前最新配置对比'" width="90%" top="5vh">
      <div class="agent-compare-toolbar">
        <span class="agent-compare-legend"><i></i>红色边框表示左右内容不一致</span>
        <span class="agent-compare-summary" :class="{ 'agent-compare-summary--same': comparisonChangedCount === 0 }">
          {{ comparisonChangedCount ? '共 ' + comparisonChangedCount + ' 处差异' : '两侧配置完全一致' }}
        </span>
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
                  <el-input :value="comparisonValue('name', side.key)" readonly :class="{ 'agent-compare-input--changed': comparisonChanged('name') }" />
                </div>
                <div class="af-field">
                  <div class="agent-compare-label"><label class="af-field__label">Agent 描述</label><em v-if="comparisonChanged('description')" class="agent-compare-badge">存在差异</em></div>
                  <el-input :value="comparisonValue('description', side.key)" type="textarea" :rows="3" readonly :class="{ 'agent-compare-input--changed': comparisonChanged('description') }" />
                </div>
              </div>
              <div class="af-field">
                <div class="agent-compare-label"><label class="af-field__label">对话模型</label><em v-if="comparisonChanged('modelId')" class="agent-compare-badge">存在差异</em></div>
                <el-input :value="comparisonValue('modelId', side.key)" readonly :class="{ 'agent-compare-input--changed': comparisonChanged('modelId') }" />
              </div>
              <div class="af-field">
                <div class="agent-compare-label"><label class="af-field__label">记忆类型</label><em v-if="comparisonChanged('chatMemoryType')" class="agent-compare-badge">存在差异</em></div>
                <el-input :value="comparisonValue('chatMemoryType', side.key)" readonly :class="{ 'agent-compare-input--changed': comparisonChanged('chatMemoryType') }" />
                <div class="agent-memory-config">
                  <div class="agent-compare-label"><label class="af-field__label">最大记忆消息数</label><em v-if="comparisonChanged('chatMemoryMaxMessages')" class="agent-compare-badge">存在差异</em></div>
                  <el-input :value="comparisonValue('chatMemoryMaxMessages', side.key)" readonly :class="{ 'agent-compare-input--changed': comparisonChanged('chatMemoryMaxMessages') }" />
                  <div class="af-form-tip">限制单个调试会话保留的历史消息数量。</div>
                </div>
              </div>
            </div>
          </section>
        </article>
      </div>
    </el-dialog>

    <!-- ------------------------------ MCP 插件选择 ------------------------------ -->
    <el-dialog class="agent-mcp-picker-dialog" :visible.sync="mcpPickerVisible" title="选择 MCP 插件" width="760px" :close-on-click-modal="false">
      <div class="af-list-toolbar">
        <el-input
          v-model.trim="mcpKeyword"
          class="af-list-toolbar__search"
          clearable
          prefix-icon="el-icon-search"
          placeholder="搜索 MCP 插件名称"
        />
        <span class="af-list-toolbar__spacer"></span>
        <span class="af-form-tip">已选 {{ mcpDraft.length }} 个插件</span>
      </div>

      <div v-loading="mcpLoading" class="agent-mcp-picker__body">
        <el-empty v-if="!mcpLoading && !mcpPlugins.length" description="暂无可用 MCP 插件，请先在「MCP 插件」页面创建" />
        <el-empty v-else-if="!mcpLoading && !mcpPagedPlugins.length" description="未找到匹配的 MCP 插件" />
        <div v-else class="agent-mcp-picker">
          <div
            v-for="plugin in mcpPagedPlugins"
            :key="plugin.id"
            class="agent-mcp-picker__plugin"
            :class="{ 'agent-mcp-picker__plugin--selected': mcpPluginChecked(plugin.id) }"
          >
            <div class="agent-mcp-picker__head">
              <el-checkbox :value="mcpPluginChecked(plugin.id)" @change="toggleMcpPlugin(plugin.id, $event)"></el-checkbox>
              <span class="agent-mcp-icon">
                <span>{{ pluginInitial(plugin) }}</span>
                <img v-if="plugin.icon" :src="plugin.icon" alt="" />
              </span>
              <div class="agent-mcp-picker__identity">
                <b>{{ plugin.name }}</b>
                <span>{{ (plugin.servers || []).length }} 个 Server{{ plugin.status === 1 ? '' : ' · 已禁用' }}</span>
              </div>
              <button
                v-if="(plugin.servers || []).length"
                type="button"
                class="agent-mcp-picker__expand"
                :aria-expanded="mcpPluginExpanded(plugin.id) ? 'true' : 'false'"
                @click="toggleMcpPluginExpanded(plugin.id)"
              >
                {{ mcpPluginExpanded(plugin.id) ? '收起服务' : '展开服务' }}
                <i class="el-icon-arrow-down" :class="{ 'is-expanded': mcpPluginExpanded(plugin.id) }"></i>
              </button>
            </div>
            <p v-if="plugin.description" class="agent-mcp-picker__desc">{{ plugin.description }}</p>
            <ul v-if="mcpPluginExpanded(plugin.id) && (plugin.servers || []).length" class="agent-mcp-picker__servers">
              <li v-for="server in plugin.servers" :key="server.id">
                <i class="el-icon-connection"></i>
                <el-checkbox
                  :value="mcpServerChecked(plugin.id, server.id)"
                  :disabled="!mcpPluginChecked(plugin.id)"
                  @change="toggleMcpServer(plugin.id, server.id, $event)"
                />
                <div class="agent-mcp-picker__server-info">
                  <div class="agent-mcp-picker__server-head">
                    <b>{{ server.serverName }}</b>
                    <el-tag size="mini" type="info" effect="plain">{{ mcpTransportLabel(server.transportType) }}</el-tag>
                    <el-tag v-if="server.status !== 1" size="mini" type="info">已禁用</el-tag>
                  </div>
                  <span :title="server.serverUrl || ''">{{ server.serverUrl || '未配置服务地址' }}</span>
                </div>
              </li>
            </ul>
            <div v-else-if="mcpPluginChecked(plugin.id) && !(plugin.servers || []).length" class="af-form-tip">该插件下暂无 Server，默认调用全部（后续新增自动生效）。</div>
          </div>
        </div>
      </div>

      <div class="af-pager">
        <span>共 {{ mcpFilteredPlugins.length }} 个插件 · 第 {{ mcpPage }} / {{ mcpTotalPages }} 页</span>
        <el-pagination
          background
          layout="sizes, prev, pager, next"
          :page-sizes="[5, 8, 20]"
          :page-size="mcpPageSize"
          :current-page="mcpPage"
          :total="mcpFilteredPlugins.length"
          @size-change="changeMcpPageSize"
          @current-change="gotoMcpPage"
        />
      </div>

      <span slot="footer">
        <el-button @click="mcpPickerVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmMcpPicker">确定</el-button>
      </span>
    </el-dialog>

    <!-- ------------------------------ 版本备注 ------------------------------ -->
    <el-dialog
      :visible.sync="remarkModalVisible"
      :title="(remarkModalHasRemark ? '编辑' : '添加') + ' V' + remarkModalVersionNo + ' 版本备注'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="88px">
        <el-form-item label="版本名称">
          <span class="af-cell__sub">{{ remarkModalVersionName || '—' }}</span>
        </el-form-item>
        <el-form-item label="版本备注">
          <el-input
            v-model="remarkDraft"
            type="textarea"
            :rows="8"
            maxlength="1024"
            show-word-limit
            placeholder="记录这个版本的改动要点、适用范围或回滚说明等（最多 1024 个字符）"
          />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button :disabled="remarkSaving" @click="closeRemarkModal">取消</el-button>
        <el-button type="primary" :loading="remarkSaving" @click="saveRemark">保存备注</el-button>
      </span>
    </el-dialog>

    <!-- ------------------------------ 提交版本确认 ------------------------------ -->
    <el-dialog :visible.sync="versionConfirmVisible" title="确认提交版本" width="520px" :close-on-click-modal="false">
      <p class="agent-version-confirm-text">将保存当前配置，并生成一条新的历史版本记录。已生成的版本不会被覆盖。</p>
      <p class="agent-version-confirm-text agent-version-confirm-hint">如果只需要保存编辑进度、不生成历史版本，请使用「仅保存」。</p>
      <el-form label-width="96px">
        <el-form-item label="版本备注">
          <el-input
            v-model.trim="versionRemarkDraft"
            type="textarea"
            :rows="3"
            maxlength="1024"
            show-word-limit
            placeholder="补充本次提交的说明，例如：优化系统提示词、新增 MCP 工具"
          />
          <div class="af-form-tip">备注会在版本历史中展示，提交后仍可随时编辑。最多 1024 个字符。</div>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button :disabled="saving" @click="versionConfirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmSubmitVersion">确认保存并提交版本</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchAgentVersions, fetchChatModels, getAgent, getAgentVersion, switchAgentVersion, updateAgentPublish, updateAgentVersionRemark, upsertAgent } from './api'
import { fetchMcpPlugins } from '../mcp/api'
import AgentDebugPanel from './AgentDebugPanel.vue'

export default {
  name: 'AgentConfigPage',
  components: { AgentDebugPanel },
  data() {
    return {
      loading: true,
      saving: false,
      publishing: false,
      historyVisible: false,
      versionLoading: false,
      switchingVersion: null,
      comparingVersion: null,
      compareVisible: false,
      comparedVersionNo: null,
      comparisonRows: [],
      versionConfirmVisible: false,
      versionRemarkDraft: '',
      remarkModalVisible: false,
      remarkModalVersionNo: null,
      remarkModalVersionName: '',
      remarkModalHasRemark: false,
      remarkDraft: '',
      remarkSaving: false,
      versions: [],
      detail: {},
      models: [],
      mcpPlugins: [],
      mcpLoading: false,
      mcpPickerVisible: false,
      mcpDraft: [],
      expandedMcpPickerPlugins: [],
      expandedMcpPlugins: [],
      mcpKeyword: '',
      mcpPage: 1,
      mcpPageSize: 5,
      memorySettingsExpanded: false,
      form: {
        id: null,
        name: '',
        description: '',
        modelId: null,
        systemPrompt: '',
        chatMemoryType: 1,
        chatMemoryMaxMessages: 1000,
        mcpPlugins: []
      },
      baseline: ''
    }
  },
  computed: {
    isPublished() { return Number(this.detail.publish) === 1 },
    isDirty() { return Boolean(this.baseline) && JSON.stringify(this.form) !== this.baseline },
    publishedChatUrl() { return '/agent/published/' + encodeURIComponent(this.detail.aid || '') },
    lastPublishTimeText() { return this.formatTime(this.detail.lastPublishTime) },
    mcpFilteredPlugins() {
      const kw = this.mcpKeyword.trim().toLowerCase()
      if (!kw) return this.mcpPlugins
      return this.mcpPlugins.filter((item) => (item.name || '').toLowerCase().includes(kw) || (item.description || '').toLowerCase().includes(kw))
    },
    mcpTotalPages() { return Math.max(1, Math.ceil(this.mcpFilteredPlugins.length / this.mcpPageSize)) },
    mcpPagedPlugins() {
      const start = (this.mcpPage - 1) * this.mcpPageSize
      return this.mcpFilteredPlugins.slice(start, start + this.mcpPageSize)
    },
    compareSides() {
      return [
        { key: 'version', title: 'V' + this.comparedVersionNo + ' 历史版本', hint: '历史配置快照' },
        { key: 'current', title: '当前最新配置', hint: '当前编辑态已保存配置' }
      ]
    },
    comparisonChangedCount() { return this.comparisonRows.filter((row) => row.changed).length }
  },
  watch: {
    mcpKeyword() { this.mcpPage = 1 },
    mcpPageSize() { this.mcpPage = 1 }
  },
  async created() {
    const id = new URLSearchParams(window.location.search).get('id')
    if (!id) {
      window.location.href = '/agents'
      return
    }
    try {
      // 三个接口并行请求，模型与插件列表失败不影响配置本体
      const [detail, modelResult, pluginList] = await Promise.all([
        getAgent(id),
        fetchChatModels().catch(() => null),
        fetchMcpPlugins('', true).catch(() => null)
      ])
      this.applyDetail(detail)
      this.models = (modelResult && modelResult.data) || []
      this.mcpPlugins = pluginList || []
    } catch (e) {
      this.toast('加载 Agent 配置失败：' + e.message, true)
    } finally {
      this.loading = false
    }
  },
  methods: {
    backToList() { window.location.href = '/agents' },
    applyDetail(detail) {
      this.detail = detail
      this.form = {
        id: detail.id,
        name: detail.name,
        description: detail.description || '',
        modelId: detail.modelId,
        systemPrompt: detail.systemPrompt || '',
        chatMemoryType: detail.chatMemoryType || 1,
        chatMemoryMaxMessages: detail.chatMemoryMaxMessages || 1000,
        mcpPlugins: (detail.mcpPlugins || []).map((item) => ({ pluginId: item.pluginId, serverIds: item.serverIds || [] }))
      }
      this.baseline = JSON.stringify(this.form)
    },
    // ---------- MCP 选择 ----------
    async openMcpPicker() {
      this.mcpPickerVisible = true
      this.mcpKeyword = ''
      this.mcpPage = 1
      this.expandedMcpPickerPlugins = []
      this.mcpLoading = true
      try {
        this.mcpPlugins = (await fetchMcpPlugins('', true)) || []
      } catch (e) {
        this.toast('加载 MCP 插件失败：' + e.message, true)
      } finally {
        this.mcpLoading = false
      }
      this.mcpDraft = this.form.mcpPlugins.map((item) => ({ pluginId: item.pluginId, serverIds: [...(item.serverIds || [])] }))
    },
    mcpPluginChecked(pluginId) { return this.mcpDraft.some((item) => Number(item.pluginId) === Number(pluginId)) },
    mcpPluginExpanded(pluginId) { return this.expandedMcpPickerPlugins.some((id) => Number(id) === Number(pluginId)) },
    toggleMcpPluginExpanded(pluginId) {
      if (this.mcpPluginExpanded(pluginId)) {
        this.expandedMcpPickerPlugins = this.expandedMcpPickerPlugins.filter((id) => Number(id) !== Number(pluginId))
      } else {
        this.expandedMcpPickerPlugins.push(pluginId)
      }
    },
    mcpServerChecked(pluginId, serverId) {
      const item = this.mcpDraft.find((entry) => Number(entry.pluginId) === Number(pluginId))
      return Boolean(item && item.serverIds.some((id) => Number(id) === Number(serverId)))
    },
    toggleMcpPlugin(pluginId, checked) {
      if (checked) this.mcpDraft.push({ pluginId, serverIds: [] })
      else this.mcpDraft = this.mcpDraft.filter((item) => Number(item.pluginId) !== Number(pluginId))
    },
    toggleMcpServer(pluginId, serverId, checked) {
      const item = this.mcpDraft.find((entry) => Number(entry.pluginId) === Number(pluginId))
      if (!item) return
      if (checked) {
        if (!item.serverIds.some((id) => Number(id) === Number(serverId))) item.serverIds.push(serverId)
      } else {
        item.serverIds = item.serverIds.filter((id) => Number(id) !== Number(serverId))
      }
    },
    gotoMcpPage(page) { if (page < 1 || page > this.mcpTotalPages) return; this.mcpPage = page },
    changeMcpPageSize(size) { this.mcpPageSize = size; this.mcpPage = 1 },
    confirmMcpPicker() {
      this.form.mcpPlugins = this.mcpDraft.map((item) => ({ pluginId: item.pluginId, serverIds: [...item.serverIds] }))
      this.mcpPickerVisible = false
    },
    mcpPluginServers(pluginId) {
      const plugin = this.mcpPlugins.find((item) => Number(item.id) === Number(pluginId))
      return (plugin && plugin.servers) || []
    },
    boundMcpServers(item) {
      const servers = this.mcpPluginServers(item.pluginId)
      const ids = (item.serverIds || []).map(Number)
      if (!ids.length) return servers
      return servers.filter((server) => ids.includes(Number(server.id)))
    },
    mcpPluginName(pluginId) {
      const plugin = this.mcpPlugins.find((item) => Number(item.id) === Number(pluginId))
      return plugin ? plugin.name : '插件 #' + pluginId
    },
    mcpPluginIcon(pluginId) {
      const plugin = this.mcpPlugins.find((item) => Number(item.id) === Number(pluginId))
      return (plugin && plugin.icon) || ''
    },
    pluginInitial(plugin) { return ((plugin && plugin.name) || 'M').trim().charAt(0).toUpperCase() || 'M' },
    mcpPluginInitial(pluginId) {
      const plugin = this.mcpPlugins.find((item) => Number(item.id) === Number(pluginId))
      return this.pluginInitial(plugin)
    },
    mcpTransportLabel(type) {
      const labels = { 1: 'Streamable HTTP', 2: 'SSE' }
      return labels[Number(type)] || '未知协议'
    },
    removeMcpPlugin(pluginId) {
      this.form.mcpPlugins = this.form.mcpPlugins.filter((item) => Number(item.pluginId) !== Number(pluginId))
    },
    async save(submitVersion) {
      if (!this.form.name || !this.form.modelId || !this.form.systemPrompt) {
        this.toast('请完整填写 Agent 配置', true)
        return false
      }
      this.saving = true
      try {
        const detail = await upsertAgent({ ...this.form, submitVersion, versionRemark: submitVersion ? this.versionRemarkDraft : '' })
        this.applyDetail(detail)
        this.toast(submitVersion ? 'Agent 配置已保存并提交 V' + detail.submittedVersionNo : 'Agent 配置已保存')
        if (this.historyVisible) await this.loadVersions()
        return true
      } catch (e) {
        this.toast('保存失败：' + e.message, true)
        return false
      } finally {
        this.saving = false
      }
    },
    openVersionConfirm() { this.versionRemarkDraft = ''; this.versionConfirmVisible = true },
    async confirmSubmitVersion() {
      this.versionConfirmVisible = false
      const saved = await this.save(true)
      if (saved) this.versionRemarkDraft = ''
    },
    // ---------- 版本备注 ----------
    openRemarkModal(version) {
      this.remarkModalVersionNo = version.versionNo
      this.remarkModalVersionName = version.name || ''
      this.remarkModalHasRemark = Boolean(version.versionRemark)
      this.remarkDraft = version.versionRemark || ''
      this.remarkModalVisible = true
    },
    closeRemarkModal() {
      if (this.remarkSaving) return
      this.remarkModalVisible = false
      this.remarkModalVersionNo = null
      this.remarkModalVersionName = ''
      this.remarkModalHasRemark = false
      this.remarkDraft = ''
    },
    async saveRemark() {
      const version = this.versions.find((item) => Number(item.versionNo) === Number(this.remarkModalVersionNo))
      if (!version) return this.closeRemarkModal()
      const versionRemark = (this.remarkDraft || '').trim()
      if (versionRemark.length > 1024) {
        this.toast('版本备注不能超过 1024 个字符', true)
        return
      }
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
      } catch (e) {
        this.toast('更新版本备注失败：' + e.message, true)
      } finally {
        this.remarkSaving = false
      }
    },
    async publish() {
      if (this.publishing) return
      const republish = this.isPublished
      if (republish && this.isDirty) {
        try {
          await this.$confirm('当前有未保存的修改，重新发布只会同步「已保存」的最新配置。是否继续？', '重新发布', { type: 'warning' })
        } catch (_e) {
          return
        }
      }
      this.publishing = 'publish'
      try {
        this.detail = await updateAgentPublish(this.detail.id, true)
        this.toast(republish ? 'Agent 已重新发布（已同步最新保存的配置）' : 'Agent 已发布')
      } catch (e) {
        this.toast((republish ? '重新发布' : '发布') + '失败：' + e.message, true)
      } finally {
        this.publishing = ''
      }
    },
    async unpublish() {
      if (this.publishing) return
      this.publishing = 'unpublish'
      try {
        this.detail = await updateAgentPublish(this.detail.id, false)
        this.toast('Agent 已停止发布')
      } catch (e) {
        this.toast('停止发布失败：' + e.message, true)
      } finally {
        this.publishing = ''
      }
    },
    async openHistory() {
      this.historyVisible = true
      await this.loadVersions()
    },
    async loadVersions() {
      this.versionLoading = true
      try {
        this.versions = (await fetchAgentVersions(this.detail.id)) || []
      } catch (e) {
        this.toast('加载版本历史失败：' + e.message, true)
      } finally {
        this.versionLoading = false
      }
    },
    async switchToVersion(version) {
      try {
        await this.$confirm('切换后将把 V' + version.versionNo + ' 的配置回填到编辑页，仅修改页面内容，不会保存。是否继续？', '切换版本', { type: 'warning' })
      } catch (_e) {
        return
      }
      this.switchingVersion = version.versionNo
      try {
        const snapshot = await switchAgentVersion(this.detail.id, version.versionNo)
        this.applyVersionToForm(snapshot)
        this.historyVisible = false
        this.toast('已载入 V' + version.versionNo + ' 配置，尚未保存')
      } catch (e) {
        this.toast('切换版本失败：' + e.message, true)
      } finally {
        this.switchingVersion = null
      }
    },
    applyVersionToForm(version) {
      const config = this.parseAgentConfig(version.agentConfig)
      this.form = {
        ...this.form,
        name: version.name,
        description: version.description || '',
        modelId: config.modelId,
        systemPrompt: config.systemPrompt,
        chatMemoryType: config.chatMemoryType,
        chatMemoryMaxMessages: config.chatMemoryMaxMessages
      }
    },
    async compareWithCurrent(version) {
      this.comparingVersion = version.versionNo
      try {
        const [historical, current] = await Promise.all([getAgentVersion(this.detail.id, version.versionNo), getAgent(this.detail.id)])
        this.comparedVersionNo = version.versionNo
        this.comparisonRows = this.buildComparisonRows(historical, current)
        this.compareVisible = true
      } catch (e) {
        this.toast('加载版本对比失败：' + e.message, true)
      } finally {
        this.comparingVersion = null
      }
    },
    closeCompare() {
      this.compareVisible = false
      this.comparedVersionNo = null
      this.comparisonRows = []
    },
    comparisonValue(key, side) {
      const row = this.comparisonRows.find((item) => item.key === key)
      return row ? row[side + 'Value'] : '—'
    },
    comparisonChanged(key) {
      const row = this.comparisonRows.find((item) => item.key === key)
      return Boolean(row && row.changed)
    },
    buildComparisonRows(historical, current) {
      const versionConfig = this.parseAgentConfig(historical.agentConfig)
      const currentConfig = {
        systemPrompt: current.systemPrompt || '',
        modelId: current.modelId,
        chatMemoryType: current.chatMemoryType || 1,
        chatMemoryMaxMessages: current.chatMemoryMaxMessages || 1000
      }
      const fields = [
        { key: 'name', label: 'Agent 名称', versionValue: historical.name || '', currentValue: current.name || '' },
        { key: 'description', label: 'Agent 描述', versionValue: historical.description || '', currentValue: current.description || '' },
        { key: 'systemPrompt', label: 'Prompt 提示词', versionValue: versionConfig.systemPrompt, currentValue: currentConfig.systemPrompt },
        { key: 'modelId', label: '对话模型', versionValue: this.modelLabel(versionConfig.modelId), currentValue: this.modelLabel(currentConfig.modelId) },
        { key: 'chatMemoryType', label: '记忆类型', versionValue: this.memoryTypeLabel(versionConfig.chatMemoryType), currentValue: this.memoryTypeLabel(currentConfig.chatMemoryType) },
        { key: 'chatMemoryMaxMessages', label: '最大记忆消息数', versionValue: String(versionConfig.chatMemoryMaxMessages), currentValue: String(currentConfig.chatMemoryMaxMessages) }
      ]
      return fields.map((field) => ({
        ...field,
        versionValue: field.versionValue || '—',
        currentValue: field.currentValue || '—',
        changed: String(field.versionValue || '') !== String(field.currentValue || '')
      }))
    },
    parseAgentConfig(raw) {
      try {
        const config = JSON.parse(raw || '{}')
        const chatModel = config.chatModel || {}
        const memory = config.chatMemoryConfig || {}
        let memoryConfig = {}
        try {
          memoryConfig = JSON.parse(memory.memoryConfig || '{}')
        } catch (_e) {
          memoryConfig = {}
        }
        return {
          systemPrompt: config.systemPrompt || '',
          modelId: chatModel.llm_model_id ?? chatModel.llmModelId ?? null,
          chatMemoryType: memory.type || 1,
          chatMemoryMaxMessages: memoryConfig.maxMessage || 1000
        }
      } catch (_e) {
        return { systemPrompt: '', modelId: null, chatMemoryType: 1, chatMemoryMaxMessages: 1000 }
      }
    },
    modelLabel(modelId) {
      const model = this.models.find((item) => Number(item.id) === Number(modelId))
      return model ? model.name + '（' + model.modelname + '）' : modelId == null ? '—' : '模型 ID: ' + modelId
    },
    memoryTypeLabel(type) { return Number(type) === 1 ? '窗口会话记忆' : '记忆类型 ' + type },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' },
    toast(text, error = false) {
      this.$message({ message: text, type: error ? 'error' : 'success', duration: 2800 })
    }
  }
}
</script>

<style lang="scss">
@use '../models/styles/tokens' as *;
.agent-config-page { min-height: 100vh; background: #f5f7fa; color: $color-text; }
.agent-config-head { height: 64px; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 0 20px 0 24px; border-bottom: 1px solid $color-border; background: #fff; }
.agent-config-head__main { display: flex; min-width: 0; align-items: center; gap: 10px; }
.agent-config-head__main > .el-button + * { margin-left: 0; }
.agent-config-back { padding: 0 2px; color: $color-text-secondary; font-size: 13px; &:hover, &:focus { color: $color-brand; } }
.agent-config-visit { display: inline-flex; align-items: center; gap: 4px; margin-left: 4px; padding-right: 4px; color: $color-brand-dark; font-size: 13px; text-decoration: none; transition: color .15s; i { font-size: 14px; } &:hover { color: $color-brand; text-decoration: underline; } }
.agent-config-head-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }
.agent-config-head-actions > .el-button + .el-button { margin-left: 0; }
.agent-config-head-actions .el-button-group > .el-button + .el-button { margin-left: -1px; }
.agent-head-divider { width: 1px; height: 18px; flex: 0 0 1px; background: $color-border-strong; }
.agent-config-title { display: flex; min-width: 0; align-items: center; gap: 4px; strong { max-width: 240px; overflow: hidden; color: $color-text; font-size: 16px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; } }
.agent-config-title__loading { color: $color-text-muted !important; font-weight: 600 !important; }
.agent-config-title__aid { overflow: hidden; color: $color-text-muted; font-family: $font-mono; font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
@keyframes agent-skeleton-shimmer { 0% { background-position: -320px 0; } 100% { background-position: 320px 0; } }
@keyframes agent-panel-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
.agent-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  span {
    height: 12px;
    border-radius: 6px;
    background: linear-gradient(90deg, #eef1f6 25%, #f7f9fc 37%, #eef1f6 63%);
    background-size: 640px 100%;
    animation: agent-skeleton-shimmer 1.15s linear infinite;
  }
  &--prompt { flex: 1; padding-top: 22px; }
  &--summary { flex-direction: row; padding: 0 0 4px; gap: 10px; span { flex: 1; height: 58px; border-radius: 8px; } }
  &--center { flex: 1; align-items: center; justify-content: center; gap: 10px; span { width: 40%; height: 14px; } }
}
.agent-skeleton-row {
  height: 38px;
  border-radius: 8px;
  background: linear-gradient(90deg, #eef1f6 25%, #f7f9fc 37%, #eef1f6 63%);
  background-size: 640px 100%;
  animation: agent-skeleton-shimmer 1.15s linear infinite;
}
.agent-debug-skeleton { min-height: 0; }
.agent-config-layout > section,
.agent-config-layout > aside { animation: agent-panel-in .26s cubic-bezier(.4, 0, .2, 1) both; }
.agent-config-layout > section:nth-child(2) { animation-delay: .04s; }
.agent-config-layout > aside:last-child { animation-delay: .08s; }
.agent-config-layout--loading .agent-config-form,
.agent-config-layout--loading textarea { pointer-events: none; }
.agent-config-layout { height: calc(100vh - 64px); display: grid; grid-template-columns: minmax(260px, .78fr) minmax(300px, .9fr) minmax(400px, 1.32fr); gap: 12px; padding: 12px; }
.agent-config-panel { display: flex; min-width: 0; flex-direction: column; overflow: hidden; border: 1px solid $color-border; border-radius: 12px; background: #fff; box-shadow: $shadow-xs; &__head { display: flex; justify-content: space-between; align-items: flex-start; padding: 16px; border-bottom: 1px solid $color-border; b { font-size: 14px; } p { margin: 5px 0 0; color: $color-text-muted; font-size: 11.5px; } } }
.agent-config-prompt textarea { flex: 1; width: 100%; resize: none; border: 0; outline: 0; padding: 18px; color: $color-text; font: 13px/1.75 $font-mono; }
.agent-config-fields { display: flex; flex-direction: column; gap: 18px; padding: 18px; overflow-y: auto; }
.agent-config-form { display: flex; flex-direction: column; gap: 4px; }
.agent-memory-settings { margin-top: 10px; overflow: hidden; border: 1px solid #dce7f3; border-radius: 9px; background: #f8fbff; }
.agent-memory-settings__head { display: flex; width: 100%; height: 40px; align-items: center; gap: 8px; padding: 0 10px; border: 0; background: #f1f6fc; cursor: pointer; font-family: inherit; line-height: 1.4; text-align: left; transition: background .15s; > div { min-width: 0; flex: 1; } b { display: block; color: $color-text-secondary; font-size: 12.5px; } &:hover, &:focus { outline: 0; background: #eaf2fb; } }
.agent-memory-settings__icon { display: inline-flex; width: 24px; height: 24px; flex: 0 0 24px; align-items: center; justify-content: center; border-radius: 6px; background: #fff; color: $color-brand; box-shadow: 0 1px 3px rgba(45, 82, 128, .09); font-size: 13px; }
.agent-memory-settings__arrow { flex: 0 0 auto; color: $color-text-muted; font-size: 12px; transition: transform .2s; &.is-expanded { transform: rotate(180deg); } }
.agent-memory-settings__field { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px; border-top: 1px solid #e3ebf4; line-height: 1.4; .el-input-number { width: 140px; flex: 0 0 140px; } }
.agent-memory-settings__label { display: flex; min-width: 0; flex-direction: column; gap: 3px; > span { color: $color-text-secondary; font-size: 12.5px; font-weight: 600; } small { color: $color-text-muted; font-size: 11px; line-height: 1.5; } }
.agent-config-group { display: flex; flex-direction: column; padding: 14px 14px 0; border: 1px solid $color-border; border-radius: 9px; }
.agent-config-group__title { color: $color-text-secondary; font-size: 13px; font-weight: 700; margin-bottom: 12px; }
.agent-config-overview { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.agent-config-summary { display: flex; flex-direction: column; gap: 6px; padding: 12px; border-radius: 8px; background: $color-sidebar; span { color: $color-text-muted; font-size: 11px; } code, b { overflow: hidden; color: $color-text-secondary; font-size: 12px; text-overflow: ellipsis; } }
.agent-mcp-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 8px; .af-form-tip { margin-top: 0; white-space: nowrap; } }
.agent-mcp-empty { padding: 10px 12px; border-radius: 8px; background: $color-sidebar; color: $color-text-muted; font-size: 12px; }
.agent-mcp-collapse { border: 1px solid $color-border; border-radius: 9px; overflow: hidden; .el-collapse-item__header { height: 46px; padding: 0 12px; border-bottom-color: $color-border; background: $color-sidebar; font-size: 12.5px; } .el-collapse-item__wrap { border-bottom-color: $color-border; background: #fff; } .el-collapse-item:last-child .el-collapse-item__header, .el-collapse-item:last-child .el-collapse-item__wrap { border-bottom: 0; } .el-collapse-item__content { padding-bottom: 0; } }
.agent-mcp-item__title { display: flex; width: 100%; min-width: 0; align-items: center; gap: 8px; b { flex: 0 1 auto; min-width: 0; overflow: hidden; font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; } }
.agent-mcp-item__remove { margin-left: auto; padding: 0 2px; font-size: 12px; }
.agent-mcp-servers { margin: 0; padding: 8px 12px 10px 26px; list-style: none; display: flex; flex-direction: column; gap: 4px; li { display: flex; align-items: center; gap: 6px; color: $color-text-secondary; font-size: 12.5px; line-height: 1.6; i { color: $color-text-muted; font-size: 13px; } } }
.agent-mcp-servers__empty { padding: 2px 0; color: $color-text-muted; font-size: 12px; }
.agent-mcp-icon { position: relative; display: inline-flex; width: 38px; height: 38px; flex: 0 0 38px; align-items: center; justify-content: center; overflow: hidden; border: 1px solid #dbe6f3; border-radius: 10px; background: linear-gradient(145deg, #edf5ff, #f8fbff); color: $color-brand; font-size: 15px; font-weight: 700; box-shadow: 0 2px 7px rgba(51, 91, 140, .08); img { position: absolute; inset: 0; width: 100%; height: 100%; background: #fff; object-fit: cover; } &--compact { width: 28px; height: 28px; flex-basis: 28px; border-radius: 7px; font-size: 12px; box-shadow: none; } }
.agent-mcp-picker-dialog { .el-dialog__body { padding-top: 16px; padding-bottom: 12px; } .el-dialog__footer { padding-top: 12px; border-top: 1px solid $color-border; } }
.agent-mcp-picker__body { height: 430px; margin-top: 14px; overflow-y: auto; padding: 3px 6px 3px 2px; border-radius: 10px; scrollbar-gutter: stable; }
.agent-mcp-picker { display: flex; flex-direction: column; gap: 10px; }
.agent-mcp-picker__plugin { padding: 14px 16px; border: 1px solid $color-border; border-radius: 11px; background: #fff; box-shadow: 0 2px 8px rgba(31, 56, 88, .035); transition: border-color .18s, box-shadow .18s, background .18s; &:hover { border-color: #bdd1ea; box-shadow: 0 5px 16px rgba(52, 92, 142, .08); } &--selected { border-color: #8eb3e5; background: #f8fbff; box-shadow: 0 0 0 2px rgba(59, 111, 182, .06); } }
.agent-mcp-picker__head { display: flex; align-items: center; gap: 11px; > .el-checkbox { margin-right: 0; } }
.agent-mcp-picker__identity { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; b { overflow: hidden; color: $color-text; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; } span { color: $color-text-muted; font-size: 11.5px; } }
.agent-mcp-picker__expand { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 5px; padding: 6px 8px; border: 0; border-radius: 6px; background: transparent; color: $color-brand-dark; cursor: pointer; font-size: 12px; transition: background .15s, color .15s; i { font-size: 11px; transition: transform .2s; &.is-expanded { transform: rotate(180deg); } } &:hover, &:focus { outline: 0; background: #eaf2fc; color: $color-brand; } }
.agent-mcp-picker__desc { margin: 10px 0 0 69px; overflow: hidden; color: $color-text-secondary; font-size: 12px; line-height: 1.55; text-overflow: ellipsis; white-space: nowrap; }
.agent-mcp-picker__servers { display: flex; flex-direction: column; gap: 7px; margin: 12px 0 0 69px; padding: 10px 12px; border: 1px solid #dce8f5; border-radius: 8px; background: #eef4fb; list-style: none; li { display: flex; min-width: 0; align-items: center; gap: 9px; padding: 8px 10px; border: 1px solid rgba(205, 220, 238, .8); border-radius: 7px; background: rgba(255, 255, 255, .72); color: $color-text-secondary; i { flex: 0 0 auto; color: #7694b8; font-size: 14px; } > .el-checkbox { flex: 0 0 auto; margin-right: 0; } } }
.agent-mcp-picker__server-info { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 4px; > span { overflow: hidden; color: $color-text-muted; font-family: $font-mono; font-size: 11px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; } }
.agent-mcp-picker__server-head { display: flex; min-width: 0; align-items: center; gap: 6px; b { min-width: 0; overflow: hidden; color: $color-text-secondary; font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; } .el-tag { flex: 0 0 auto; height: 20px; line-height: 18px; } }
.agent-mcp-picker-dialog .af-pager { margin-top: 12px; }
.af-text-danger { color: #d64545 !important; &:hover, &:focus { color: #f56c6c !important; } }
.agent-version-table { margin-top: 12px; }
.agent-version-no { color: $color-brand; font-size: 13px; }
.agent-version-confirm-text { margin: 0 0 8px; color: $color-text-secondary; font-size: 13px; line-height: 1.7; }
.agent-version-confirm-hint { color: $color-text-muted; font-size: 12px; }
.agent-compare-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.agent-compare-legend { display: inline-flex; align-items: center; gap: 7px; color: $color-text-secondary; font-size: 12px; i { width: 14px; height: 14px; border: 2px solid #e45b52; border-radius: 4px; background: #fff8f7; } }
.agent-compare-summary { padding: 3px 10px; border-radius: 10px; background: #fff0ef; color: #c9423a; font-size: 12px; &--same { background: #eaf7f0; color: #1d8a55; } }
.agent-compare-columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; align-items: start; }
.agent-compare-column { display: flex; min-width: 0; flex-direction: column; gap: 12px; }
.agent-compare-column-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding: 0 2px; b { color: $color-text; font-size: 15px; } span { color: $color-text-muted; font-size: 11px; } }
.agent-compare-prompt { height: 340px; flex: 0 0 340px; &--changed { border: 2px solid #e45b52; background: #fffafa; box-shadow: 0 0 0 3px rgba(228, 91, 82, .08); } .agent-config-panel__head { padding-right: 12px; } }
.agent-compare-prompt-text { flex: 1; min-height: 0; margin: 0; overflow: auto; padding: 18px; color: $color-text; font: 13px/1.75 $font-mono; white-space: pre-wrap; word-break: break-word; }
.agent-compare-details { flex: 1; }
.agent-compare-details .agent-config-fields { padding: 18px; }
.agent-compare-label { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 18px; margin-bottom: 6px; }
.agent-compare-badge { padding: 1px 7px; border-radius: 10px; background: #fff0ef; color: #d33f36; font-size: 11px; font-style: normal; white-space: nowrap; }
.agent-compare-input--changed .el-input__inner, .agent-compare-input--changed .el-textarea__inner { border: 2px solid #e45b52; background: #fff8f7; }
.agent-compare-details .agent-memory-config { margin-top: 12px; padding: 12px; border-radius: 8px; background: $color-sidebar; }
.agent-debug-head { gap: 10px; }
.agent-debug-head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
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
@media (max-width: 1280px) { .agent-config-title__loading { color: $color-text-muted !important; font-weight: 600 !important; }
.agent-config-title__aid { display: none; } .agent-config-title strong { max-width: 160px; } }
@media (max-width: 1100px) { .agent-config-layout { height: auto; grid-template-columns: 1fr 1fr; } .agent-debug { min-height: 520px; grid-column: 1 / -1; } }
@media (max-width: 900px) { .agent-config-head { height: auto; flex-wrap: wrap; gap: 8px; padding: 10px 12px; } .agent-config-head-actions { flex-wrap: wrap; } .agent-head-divider { display: none; } }
@media (max-width: 720px) { .agent-config-layout { grid-template-columns: 1fr; } .agent-config-title strong { max-width: 120px; } }
</style>
