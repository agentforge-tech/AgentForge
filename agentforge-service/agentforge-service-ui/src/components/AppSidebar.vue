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
        router
        :default-active="activeIndex"
        :default-openeds="defaultOpeneds"
      >
        <el-submenu index="resources">
          <template slot="title">
            <AppNavIcon name="components" />
            <span>Agent组件</span>
          </template>
          <el-menu-item index="/">
            <AppNavIcon name="models" />
            <span slot="title">模型列表</span>
          </el-menu-item>
          <el-submenu index="plugins">
            <template slot="title">
              <AppNavIcon name="plugins" />
              <span>插件列表</span>
            </template>
            <el-menu-item index="/mcp">
              <AppNavIcon name="mcp" />
              <span slot="title">MCP 插件</span>
            </el-menu-item>
            <el-menu-item index="/http-plugins">
              <AppNavIcon name="http" />
              <span slot="title">HTTP 插件</span>
            </el-menu-item>
          </el-submenu>
        </el-submenu>

        <el-submenu index="agent">
          <template slot="title">
            <AppNavIcon name="cockpit" />
            <span>Agent 驾驶舱</span>
          </template>
          <el-menu-item index="/agents">
            <AppNavIcon name="agents" />
            <span slot="title">Agent 列表</span>
          </el-menu-item>
          <el-menu-item index="/agent-trace">
            <AppNavIcon name="trace" />
            <span slot="title">Agent Trace 监控</span>
          </el-menu-item>
        </el-submenu>
      </el-menu>
    </nav>

    <div class="af-sidebar__footer"><AppUserPanel /></div>
  </aside>
</template>

<script>
import AppUserPanel from './AppUserPanel.vue'
import AppNavIcon from './AppNavIcon.vue'

export default {
  name: 'AppSidebar',
  components: { AppNavIcon, AppUserPanel },
  data: () => ({ defaultOpeneds: ['resources', 'plugins', 'agent'] }),
  computed: {
    activeIndex() {
      const path = this.$route.path
      if (path.startsWith('/agents')) return '/agents'
      if (path === '/mcp') return '/mcp'
      if (path === '/http-plugins') return '/http-plugins'
      if (path === '/agent-trace') return '/agent-trace'
      return '/'
    }
  }
}
</script>
