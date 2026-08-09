export const TASK_STATUS_META = Object.freeze({
  PENDING: { label: '排队中', type: 'info' },
  PROCESSING: { label: '处理中', type: 'primary' },
  RETRY_WAIT: { label: '等待重试', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  PENDING_USER: { label: '待确认', type: 'warning' },
  EXPIRED: { label: '已过期', type: 'info' },
  DEAD_LETTER: { label: '超过重试上限', type: 'danger' },
})

export function taskStatusMeta(status) {
  return TASK_STATUS_META[status] || { label: status || '未知状态', type: 'info' }
}
