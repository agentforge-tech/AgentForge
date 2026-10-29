import Vue from 'vue'
import VueRouter from 'vue-router'

Vue.use(VueRouter)

const routes = [
  {
    path: '/',
    name: 'models',
    component: () => import('../pages/models/ModelsPage.vue'),
    meta: { title: '我的模型' }
  },
  {
    path: '/agents',
    name: 'agents',
    component: () => import('../pages/agents/AgentsPage.vue'),
    meta: { title: 'Agent 列表' }
  },
  {
    path: '/agents/config',
    name: 'agent-config',
    component: () => import('../pages/agents/AgentConfigPage.vue'),
    meta: { title: 'Agent 配置' }
  },
  {
    path: '/mcp',
    name: 'mcp',
    component: () => import('../pages/mcp/McpPage.vue'),
    meta: { title: 'MCP 插件' }
  },
  {
    path: '/http-plugins',
    name: 'http-plugins',
    component: () => import('../pages/http/HttpPluginPage.vue'),
    meta: { title: 'HTTP 插件' }
  },
  {
    path: '/agent-trace',
    name: 'agent-trace',
    component: () => import('../pages/trace/AgentTracePage.vue'),
    meta: { title: 'Agent Trace 监控' }
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../pages/published/PublishedAgentChatPage.vue'),
    meta: { title: '对话' }
  },
  {
    path: '/agent/published/:aid',
    name: 'published-agent',
    component: () => import('../pages/published/PublishedAgentChatPage.vue'),
    meta: { title: '对话' }
  },
  {
    path: '*',
    redirect: '/'
  }
]

const router = new VueRouter({
  mode: 'history',
  routes,
  scrollBehavior: () => ({ x: 0, y: 0 })
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · AgentForge` : 'AgentForge'
})

export default router
