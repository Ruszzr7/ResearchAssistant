/**
 * Vue Router 配置 —— SPA 路由表，全部使用懒加载（代码分割）。
 */
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/',          name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
  { path: '/library',   name: 'library',   component: () => import('@/views/LibraryView.vue') },
  { path: '/search',    name: 'search',    component: () => import('@/views/SearchView.vue') },
  { path: '/analysis',  name: 'analysis',  component: () => import('@/views/AnalysisView.vue') },
  { path: '/gap',       name: 'gap',       component: () => import('@/views/GapView.vue') },
  { path: '/tasks',     name: 'tasks',     component: () => import('@/views/TaskCenterView.vue') },
  { path: '/reading-plans', name: 'reading-plans', component: () => import('@/views/ReadingPlanView.vue') },
  { path: '/writing',     name: 'writing',     component: () => import('@/views/WritingView.vue') },
  { path: '/settings',  name: 'settings',  component: () => import('@/views/SettingsView.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
