import { computed } from 'vue'
import { ensureGlobalTask } from '@/stores/globalTaskStore.js'
import { isCancelError } from '@/utils/cancel.js'

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

  function setStage(text) {
    task.statusText = text
  }

  async function run(taskFn) {
    // 新任务启动前 abort 旧任务，防止竞态
    if (task.controller) {
      task.controller.abort()
    }

    task.lastTaskFn = taskFn
    task.error = null
    task.isLoading = true
    task.statusText = ''

    const controller = new AbortController()
    task.controller = controller

    try {
      return await taskFn({ signal: controller.signal, setStage, data: task.data })
    } catch (e) {
      if (isCancelError(e) || controller.signal.aborted) {
        return undefined
      }
      task.error = e
      return undefined
    } finally {
      if (task.controller === controller) {
        task.controller = null
        task.isLoading = false
        task.statusText = ''
      }
    }
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
