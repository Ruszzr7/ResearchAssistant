import { describe, expect, it } from 'vitest'
import { outputLabel, planCompletion } from '@/utils/readingPlan.js'

describe('goal-driven reading plan helpers', () => {
  it('computes bounded outcome completion', () => {
    expect(planCompletion(2, 3)).toBe(67)
    expect(planCompletion(5, 2)).toBe(100)
    expect(planCompletion(0, 0)).toBe(0)
  })

  it('uses a stable product label for persisted output types', () => {
    expect(outputLabel('METHOD_MAP')).toBe('方法与假设图谱')
    expect(outputLabel('UNKNOWN')).toBe('核心结论摘要')
  })
})
