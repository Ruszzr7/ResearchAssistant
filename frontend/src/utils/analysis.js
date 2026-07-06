/**
 * 轮询等待论文 AI 分析完成。
 *
 * @param {Function} apiGet - 形如 (url, config) => Promise 的请求函数
 * @param {string|number} paperId
 * @param {AbortSignal} [signal]
 * @param {Function} [onData] - 每次拿到数据时的回调
 * @returns {Promise<void>}
 */
export function waitForAnalysis(apiGet, paperId, signal, onData) {
  const MAX_ATTEMPTS = 40
  const INTERVAL = 3000

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
        const r = await apiGet(`/agent/analysis/${paperId}`, { signal })
        const data = r.data
        if (data) {
          onData?.(data)
          if (data.processingStatus === 'COMPLETED' || (data.rawText && data.rawText.trim())) {
            settled = true
            cleanup()
            resolve()
            return
          }
          if (data.processingStatus === 'FAILED') {
            settled = true
            cleanup()
            reject(new Error('AI 分析失败，请检查论文内容或后端日志'))
            return
          }
        }
      } catch (e) {
        // 单次轮询失败忽略，继续下一轮
      }

      if (attempts >= MAX_ATTEMPTS) {
        settled = true
        cleanup()
        reject(new Error('分析准备超时，请稍后刷新页面重试'))
        return
      }
      pollTimer = setTimeout(tick, INTERVAL)
    }

    tick()
  })
}
