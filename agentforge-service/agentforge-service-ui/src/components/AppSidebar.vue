<template>
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
      <el-menu
        class="af-menu"
        :default-active="activeIndex"
        :default-openeds="defaultOpeneds"
        @select="onSelect"
      >
        <el-submenu index="resources">
          <template slot="title">
            <i class="el-icon-folder-opened"></i>
            <span>我的资源库</span>
          </template>
          <el-menu-item index="/">
            <i class="el-icon-cpu"></i>
            <span slot="title">模型列表</span>
          </el-menu-item>
          <el-menu-item index="/mcp">
            <i class="el-icon-connection"></i>
            <span slot="title">MCP 插件</span>
          </el-menu-item>
        </el-submenu>

        <el-submenu index="agent">
          <template slot="title">
            <i class="el-icon-s-platform"></i>
            <span>Agent 驾驶舱</span>
          </template>
          <el-menu-item index="/agents">
            <i class="el-icon-s-custom"></i>
            <span slot="title">Agent 列表</span>
          </el-menu-item>
        </el-submenu>
      </el-menu>
    </nav>

    <div class="af-sidebar__footer"><AppUserPanel /></div>
  </aside>
</template>

<script>
import AppUserPanel from './AppUserPanel.vue'

const ACTIVE_INDEX = { models: '/', mcp: '/mcp', agents: '/agents' }

export default {
  name: 'AppSidebar',
  components: { AppUserPanel },
  props: { active: { type: String, required: true, validator: (value) => ['agents', 'models', 'mcp'].includes(value) } },
  data: () => ({ defaultOpeneds: ['resources', 'agent'] }),
  computed: {
    activeIndex() { return ACTIVE_INDEX[this.active] || '/' }
  },
  methods: {
    // 多页面（MPA）站点，导航直接跳转，不做前端路由
    onSelect(index) {
      if (!index || index === this.activeIndex) return
      window.location.href = index
    }
  }
}
</script>
