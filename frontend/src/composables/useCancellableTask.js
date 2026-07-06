import { ref, onUnmounted } from 'vue'
import { isCancelError } from '@/utils/cancel.js'

/**
 * 可取消任务管理 composable。
 *
 * 统一处理：loading 状态、阶段提示、错误、取消、重试。
 * taskFn 接收 { signal, setStage }，可通过 signal 取消 axios/fetch 请求，
 * 通过 setStage 更新当前阶段提示文案。
 */
export function useCancellableTask() {
  const isLoading = ref(false)
  const statusText = ref('')
  const error = ref(null)

  let currentController = null
  let lastTaskFn = null

  function setStage(text) {
    statusText.value = text
  }

  async function run(taskFn) {
    // 新任务启动前 abort 旧任务，防止竞态
    if (currentController) {
      currentController.abort()
    }

    lastTaskFn = taskFn
    error.value = null
    isLoading.value = true
    statusText.value = ''

    const controller = new AbortController()
    currentController = controller

    try {
      return await taskFn({ signal: controller.signal, setStage })
    } catch (e) {
      // 用户主动取消时静默处理，不视为错误
      if (isCancelError(e) || controller.signal.aborted) {
        return undefined
      }
      // 错误已记录到 error.value，不再向上抛出，避免 Vue 事件处理器未处理异常
      error.value = e
      return undefined
    } finally {
      if (currentController === controller) {
        currentController = null
        isLoading.value = false
        statusText.value = ''
      }
    }
  }

  function cancel() {
    if (currentController) {
      currentController.abort()
      // 不在这里清空 currentController；由 run 的 finally 统一重置状态，
      // 避免竞态导致 loading 无法结束。
    }
  }

  function retry() {
    if (lastTaskFn) {
      return run(lastTaskFn)
    }
  }

  onUnmounted(() => cancel())

  return { isLoading, statusText, error, run, cancel, retry }
}
