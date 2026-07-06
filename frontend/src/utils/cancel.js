/**
 * 判断错误是否由请求取消/中断导致。
 * 统一处理 AbortController、axios CancelToken 等产生的取消错误。
 */
export function isCancelError(err) {
  if (!err) return false
  return (
    err.name === 'AbortError' ||
    err.name === 'CanceledError' ||
    err.code === 'ERR_CANCELED' ||
    err.message === 'canceled' ||
    err.message?.includes('aborted') ||
    err.message?.includes('cancel')
  )
}
