import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import 'highlight.js/styles/github.css'
import './styles.css'
import PublishedAgentChatPage from './pages/published/PublishedAgentChatPage.vue'

Vue.use(ElementUI)
Vue.config.productionTip = false
new Vue({ render: (h) => h(PublishedAgentChatPage) }).$mount('#app')
