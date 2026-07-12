import { describe, expect, it } from 'vitest'
import { isTerminal } from '@/composables/useTaskPolling.js'
import { poll } from '@/utils/task.js'

describe('task lifecycle helpers', () => {
  it('recognizes all terminal task states', () => {
    expect(isTerminal('COMPLETED')).toBe(true)
    expect(isTerminal('DEAD_LETTER')).toBe(true)
    expect(isTerminal('PROCESSING')).toBe(false)
  })

  it('resolves polling when a task becomes complete', async () => {
    const values = [{ status: 'PROCESSING' }, { status: 'COMPLETED', result: 42 }]
    const result = await poll({
      fetch: async () => values.shift(),
      isCompleted: value => value.status === 'COMPLETED',
      isFailed: () => false,
      getError: () => 'failed',
      maxAttempts: 3,
      interval: 0,
    })
    expect(result.result).toBe(42)
  })
})
