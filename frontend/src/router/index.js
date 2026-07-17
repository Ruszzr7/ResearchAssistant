/**
 * Vue Router 配置 —— SPA 路由表，全部使用懒加载（代码分割）。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { legacyWorkbenchRedirect } from '@/router/workbenchRoute.js'

const loadLibraryView = () => import('@/views/LibraryView.vue')

const routes = [
  { path: '/',          name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
  { path: '/library',   name: 'library',   component: loadLibraryView },
  { path: '/search',    name: 'search',    component: () => import('@/views/SearchView.vue') },
  { path: '/research/:paperId?', name: 'research', component: () => import('@/views/PaperResearchView.vue') },
  { path: '/workbench', redirect: to => legacyWorkbenchRedirect(to, 'analysis') },
  { path: '/analysis',  redirect: to => legacyWorkbenchRedirect(to, 'analysis') },
  { path: '/gap',       redirect: to => legacyWorkbenchRedirect(to, 'gap') },
  { path: '/archive',   name: 'archive',   component: () => import('@/views/ResearchArchiveView.vue') },
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
