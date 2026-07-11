import { computed } from 'vue'
import { ensureGlobalTask } from '@/stores/globalTaskStore.js'
import { runTask } from '@/composables/taskRunner.js'

/**
 * 全局后台任务 composable。
 *
 * 与 useCancellableTask 行为一致，但状态保存在全局 store 中。
 * 组件卸载时不会自动取消任务，切换页面回来后仍可看到任务进度。
 *
 * @param {string} taskId - 全局唯一任务标识
 */
export function useGlobalTask(taskId) {
  const task = ensureGlobalTask(taskId)

  async function run(taskFn) {
    return runTask(task, taskFn, task.data)
  }

  function cancel() {
    if (task.controller) {
      task.controller.abort()
    }
  }

  function retry() {
    if (task.lastTaskFn) {
      return run(task.lastTaskFn)
    }
  }

  return {
    isLoading: computed(() => task.isLoading),
    statusText: computed(() => task.statusText),
    error: computed(() => task.error),
    data: task.data,
    run,
    cancel,
    retry
  }
}
