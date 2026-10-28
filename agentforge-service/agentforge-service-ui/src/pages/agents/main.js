import Vue from 'vue'
import '../../plugins/element'
import AgentsPage from './AgentsPage.vue'
import AgentConfigPage from './AgentConfigPage.vue'
import '../models/styles/models.scss'

Vue.config.productionTip = false
const Page = window.location.pathname.startsWith('/agents/config') ? AgentConfigPage : AgentsPage
new Vue({ render: (h) => h(Page) }).$mount('#agents-app')
