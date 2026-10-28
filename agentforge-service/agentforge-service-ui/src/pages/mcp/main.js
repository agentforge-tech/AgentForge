import Vue from 'vue'
import '../../plugins/element'
import McpPage from './McpPage.vue'
import '../models/styles/models.scss'

Vue.config.productionTip = false
new Vue({ render: (h) => h(McpPage) }).$mount('#mcp-app')
