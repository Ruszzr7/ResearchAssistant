/**
 * Vue 应用入口 —— 挂载 Router、Element Plus 到 #app。
 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import VXETable from 'vxe-table'
import VxeUI from 'vxe-pc-ui'
import 'vxe-table/lib/style.css'
import 'vxe-pc-ui/lib/style.css'
import App from './App.vue'
import router from './router'
import './stores/themeStore.js'

const app = createApp(App)
app.use(router)           // 页面路由
app.use(ElementPlus)      // UI 组件库
app.use(VxeUI)            // vxe-table UI 依赖
app.use(VXETable)         // 表格组件
app.mount('#app')
