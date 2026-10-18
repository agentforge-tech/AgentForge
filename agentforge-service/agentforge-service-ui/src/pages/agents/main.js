import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import AgentsPage from './AgentsPage.vue'
import AgentConfigPage from './AgentConfigPage.vue'
import '../models/styles/models.scss'

Vue.config.productionTip = false
Vue.use(ElementUI)
const Page = window.location.pathname.startsWith('/agents/config') ? AgentConfigPage : AgentsPage
new Vue({ render: (h) => h(Page) }).$mount('#agents-app')
