/**
 * Vue Router 配置 —— SPA 路由表，全部使用懒加载（代码分割）。
 */
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/',          name: 'library',  component: () => import('@/views/LibraryView.vue') },
  { path: '/search',    name: 'search',   component: () => import('@/views/SearchView.vue') },
  { path: '/analysis',  name: 'analysis', component: () => import('@/views/AnalysisView.vue') },
  { path: '/gap',       name: 'gap',      component: () => import('@/views/GapView.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
