/**
 * 通用轮询辅助函数。
 *
 * @param {Object} options
 * @param {Function} options.fetch - 返回 Promise<data> 的异步获取函数
 * @param {Function} options.isCompleted - data => boolean，判断任务是否完成
 * @param {Function} options.isFailed - data => boolean，判断任务是否失败
 * @param {Function} options.getError - data => string，失败时生成错误信息
 * @param {Function} [options.onData] - data => void，每次拿到数据时的回调
 * @param {AbortSignal} [options.signal]
 * @param {number} [options.maxAttempts=60]
 * @param {number} [options.interval=3000]
 * @param {string} [options.timeoutMessage='轮询超时']
 * @returns {Promise<any>} 最后一次 fetch 返回的数据
 */
export function poll({
  fetch,
  isCompleted,
  isFailed,
  getError,
  onData,
  signal,
  maxAttempts = 60,
  interval = 3000,
  timeoutMessage = '轮询超时'
}) {
  return new Promise((resolve, reject) => {
    let attempts = 0
    let settled = false
    let pollTimer = null

    const cleanup = (aborted = false) => {
      if (pollTimer) {
        clearTimeout(pollTimer)
        pollTimer = null
      }
      if (aborted && !settled) {
        settled = true
        reject(new Error('aborted'))
      }
    }

    if (signal) {
      signal.addEventListener('abort', () => cleanup(true), { once: true })
    }

    async function tick() {
      if (settled || signal?.aborted) {
        cleanup(true)
        return
      }
      attempts++
      try {
        const data = await fetch()
        if (data) {
          onData?.(data)
          if (isCompleted(data)) {
            settled = true
            cleanup()
            resolve(data)
            return
          }
          if (isFailed(data)) {
            settled = true
            cleanup()
            reject(new Error(getError(data)))
            return
          }
        }
      } catch (e) {
        // 单次轮询失败忽略，继续下一轮
      }

      if (attempts >= maxAttempts) {
        settled = true
        cleanup()
        reject(new Error(timeoutMessage))
        return
      }
      pollTimer = setTimeout(tick, interval)
    }

    tick()
  })
}

/**
 * 轮询等待异步任务完成。
 *
 * @param {Function} apiGet - 形如 (url, config) => Promise 的请求函数
 * @param {string} taskId
 * @param {AbortSignal} [signal]
 * @param {Function} [onStage] - 每次拿到阶段文案时的回调
 * @returns {Promise<any>} 任务完成后的 result
 */
export function waitForTask(apiGet, taskId, signal, onStage) {
  return poll({
    fetch: () => apiGet(`/agent/task/${taskId}`, { signal }).then(r => r.data),
    isCompleted: data => data?.status === 'COMPLETED',
    isFailed: data => ['FAILED', 'CANCELLED', 'EXPIRED'].includes(data?.status),
    getError: data =>
      data?.status === 'CANCELLED' ? '任务已取消' : (data?.error || '任务执行失败'),
    onData: data => onStage?.(data.stageText),
    signal,
    maxAttempts: 60,
    interval: 3000,
    timeoutMessage: '任务等待超时，请稍后刷新页面重试'
  }).then(data => data.result)
}
