import Vue from 'vue'
import '../../plugins/element'
import HttpPluginPage from './HttpPluginPage.vue'
import '../models/styles/models.scss'

Vue.config.productionTip = false
new Vue({ render: (h) => h(HttpPluginPage) }).$mount('#http-plugin-app')
