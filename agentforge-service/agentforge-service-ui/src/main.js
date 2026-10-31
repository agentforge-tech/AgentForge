import Vue from 'vue'
import './plugins/element'
import './styles/admin/admin.scss'
import './styles/chat.css'
import './styles/chat-scroll-button.css'
import App from './App.vue'
import router from './router'

Vue.config.productionTip = false
new Vue({ router, render: (h) => h(App) }).$mount('#app')
