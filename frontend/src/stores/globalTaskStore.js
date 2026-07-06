import { reactive } from 'vue'

/**
 * 全局后台任务状态管理。
 *
 * 用于支撑「切换页面后任务继续运行」的需求。
 * 每个任务通过唯一 taskId 在全局 store 中保存：
 * - isLoading / statusText / error：任务运行状态
 * - controller：当前任务的 AbortController，支持取消
 * - lastTaskFn：上次执行的任务函数，用于重试
 * - data：任务相关的持久化数据（由具体业务写入，如 extraction、results）
 */
export const globalTaskStore = reactive({
  tasks: {}
})

export function ensureGlobalTask(taskId) {
  if (!globalTaskStore.tasks[taskId]) {
    globalTaskStore.tasks[taskId] = {
      isLoading: false,
      statusText: '',
      error: null,
      controller: null,
      lastTaskFn: null,
      data: {}
    }
  }
  return globalTaskStore.tasks[taskId]
}
