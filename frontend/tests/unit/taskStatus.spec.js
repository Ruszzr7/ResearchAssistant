import { describe, expect, it } from 'vitest'
import { TASK_STATUS_META, taskStatusMeta } from '@/utils/taskStatus.js'

describe('shared task status presentation', () => {
  it('uses one label and color source for dashboard and task center', () => {
    expect(TASK_STATUS_META.PROCESSING).toEqual({ label: '处理中', type: 'primary' })
    expect(TASK_STATUS_META.CANCELLED).toEqual({ label: '已取消', type: 'info' })
    expect(taskStatusMeta('COMPLETED')).toEqual({ label: '已完成', type: 'success' })
  })

  it('falls back safely for a future backend status', () => {
    expect(taskStatusMeta('PAUSED')).toEqual({ label: 'PAUSED', type: 'info' })
  })
})
