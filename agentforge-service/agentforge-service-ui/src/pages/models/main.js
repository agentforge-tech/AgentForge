import Vue from 'vue'
import '../../plugins/element'
import ModelsPage from './ModelsPage.vue'
import './styles/models.scss'

Vue.config.productionTip = false
new Vue({ render: (h) => h(ModelsPage) }).$mount('#models-app')
