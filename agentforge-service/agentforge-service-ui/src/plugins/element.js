// ---------------------------------------------------------------------------
// Element UI 统一注册入口
// 所有页面入口（models / agents / mcp / published）都从这里挂载组件库，
// 保证组件、主题样式与中文语言包只被注册一次。
// ---------------------------------------------------------------------------
import Vue from 'vue'
import ElementUI from 'element-ui'
import locale from 'element-ui/lib/locale/lang/zh-CN'
import 'element-ui/lib/theme-chalk/index.css'

Vue.use(ElementUI, { locale, size: 'small', zIndex: 2000 })

export default ElementUI
