import { describe, expect, it } from 'vitest'
import {
  evaluationHealth,
  formatMetricDuration,
  formatMetricPercent,
  workflowMetricRows,
} from '@/utils/workbenchMetrics.js'

describe('PDF workbench metric presentation', () => {
  it('reports deterministic and optional real evaluation health honestly', () => {
    expect(evaluationHealth({
      deterministicCases: 7,
      deterministicPassed: 7,
      realExecuted: 1,
      realPassed: 1,
      realSkipped: 3,
    })).toEqual({ healthy: true, deterministic: '7/7', real: '1/1', realSkipped: 3 })
    expect(evaluationHealth({ deterministicCases: 7, deterministicPassed: 6 }))
      .toMatchObject({ healthy: false, real: '未配置' })
  })

  it('formats bounded rates and durations', () => {
    expect(formatMetricPercent(0.9561, 1)).toBe('95.6%')
    expect(formatMetricPercent(2)).toBe('100%')
    expect(formatMetricDuration(720)).toBe('720ms')
    expect(formatMetricDuration(2500)).toBe('2.5s')
    expect(formatMetricDuration(90000)).toBe('1.5min')
  })

  it('normalizes all workflow rows for the dashboard', () => {
    expect(workflowMetricRows({ workflows: [{
      workflow: 'PAPER_COMPARISON', total: 2, completed: 1, failed: 1, cancelled: 0,
      completionRate: 0.5, repairRate: 0.25,
      averageLatencyMs: 1200, averageTokens: 1234.4, averageEvidence: 9.5,
    }] })).toEqual([{
      workflow: 'PAPER_COMPARISON', label: '多篇对比', total: 2, terminal: 2, completed: 1,
      completionRate: 0.5, repairRate: 0.25,
      averageLatency: '1.2s', averageTokens: 1234, averageEvidence: 9.5,
    }])
    expect(workflowMetricRows({ workflows: [{ workflow: 'RESEARCH_GAP' }] })[0].label)
      .toBe('研究 Gap')
  })
})
