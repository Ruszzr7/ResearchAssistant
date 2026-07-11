import { reactive, toRefs, onUnmounted } from 'vue'
import { runTask } from '@/composables/taskRunner.js'

/**
 * 可取消任务管理 composable。
 *
 * 统一处理：loading 状态、阶段提示、错误、取消、重试。
 * taskFn 接收 { signal, setStage }，可通过 signal 取消 axios/fetch 请求，
 * 通过 setStage 更新当前阶段提示文案。
 */
export function useCancellableTask() {
  const state = reactive({
    isLoading: false,
    statusText: '',
    error: null,
    controller: null,
    lastTaskFn: null
  })

  async function run(taskFn) {
    return runTask(state, taskFn)
  }

  function cancel() {
    if (state.controller) {
      state.controller.abort()
    }
  }

  function retry() {
    if (state.lastTaskFn) {
      return run(state.lastTaskFn)
    }
  }

  onUnmounted(() => cancel())

  const { isLoading, statusText, error } = toRefs(state)
  return { isLoading, statusText, error, run, cancel, retry }
}
