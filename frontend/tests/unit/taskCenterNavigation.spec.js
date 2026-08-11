import { describe, expect, it } from 'vitest'
import { taskCenterNavigation } from '@/utils/taskCenterNavigation.js'

describe('task center navigation', () => {
  it('returns to the screen from which the task center was opened', () => {
    const opened = taskCenterNavigation('/research?paperId=188')
    expect(opened).toEqual({ target: '/tasks', nextReturnPath: '/research?paperId=188' })

    expect(taskCenterNavigation('/tasks', opened.nextReturnPath)).toEqual({
      target: '/research?paperId=188', nextReturnPath: '',
    })
  })

  it('falls back to the dashboard when no return screen was recorded', () => {
    expect(taskCenterNavigation('/tasks', '')).toEqual({ target: '/', nextReturnPath: '' })
  })
})
