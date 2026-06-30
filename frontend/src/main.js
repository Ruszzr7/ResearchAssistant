/**
 * Vue 应用入口 —— 挂载 Pinia、Router、Element Plus 到 #app。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())    // 状态管理（尚未使用，预留）
app.use(router)           // 页面路由
app.use(ElementPlus)      // UI 组件库
app.mount('#app')
