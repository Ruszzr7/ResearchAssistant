import { poll } from './task.js'

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
  return poll({
    fetch: () => apiGet(`/research-automation/analysis/${paperId}`, { signal }).then(r => r.data),
    isCompleted: data =>
      data?.processingStatus === 'COMPLETED' || !!data?.rawText?.trim(),
    isFailed: data => data?.processingStatus === 'FAILED',
    getError: () => 'AI 分析失败，请检查论文内容或后端日志',
    onData,
    signal,
    maxAttempts: 40,
    interval: 3000,
    timeoutMessage: '分析准备超时，请稍后刷新页面重试'
  }).then(() => {})
}
