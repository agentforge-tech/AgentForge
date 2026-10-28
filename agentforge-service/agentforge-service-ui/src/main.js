import Vue from 'vue'
import './plugins/element'
import 'highlight.js/styles/github.css'
import './styles.css'
import PublishedAgentChatPage from './pages/published/PublishedAgentChatPage.vue'

Vue.config.productionTip = false
new Vue({ render: (h) => h(PublishedAgentChatPage) }).$mount('#app')
