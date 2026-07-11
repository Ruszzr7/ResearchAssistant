import { isCancelError } from '@/utils/cancel.js'

/** 统一本地任务与全局任务的取消、重试和错误处理。 */
export async function runTask(state, taskFn, data) {
  if (state.controller) {
    state.controller.abort()
  }

  state.lastTaskFn = taskFn
  state.error = null
  state.isLoading = true
  state.statusText = ''

  const controller = new AbortController()
  state.controller = controller
  const setStage = text => { state.statusText = text }

  try {
    return await taskFn({ signal: controller.signal, setStage, data })
  } catch (error) {
    if (isCancelError(error) || controller.signal.aborted) {
      return undefined
    }
    state.error = error
    return undefined
  } finally {
    if (state.controller === controller) {
      state.controller = null
      state.isLoading = false
      state.statusText = ''
    }
  }
}
