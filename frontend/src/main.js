/**
 * Vue 应用入口 —— 挂载 Router、Element Plus 到 #app。
 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'katex/dist/katex.min.css'
import VxeUI from 'vxe-pc-ui'
import 'vxe-pc-ui/lib/style.css'
import App from './App.vue'
import router from './router'
import './stores/themeStore.js'

const app = createApp(App)
app.use(router)           // 页面路由
app.use(ElementPlus, { locale: zhCn })  // UI 组件库（中文默认文案）
app.use(VxeUI)            // vxe-table UI 依赖
app.mount('#app')
