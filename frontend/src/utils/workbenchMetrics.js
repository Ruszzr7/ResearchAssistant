const WORKFLOW_LABELS = Object.freeze({
  SELECTION_QA: '选区问答',
  PAPER_ANALYSIS: '全文分析',
  ANNOTATION_SUGGESTION: '批注建议',
  PAPER_COMPARISON: '多篇对比',
  RESEARCH_GAP: '研究 Gap',
})

export function formatMetricPercent(value, digits = 0) {
  const rate = Math.max(0, Math.min(1, Number(value) || 0))
  return `${(rate * 100).toFixed(digits)}%`
}

export function formatMetricDuration(milliseconds) {
  const value = Math.max(0, Number(milliseconds) || 0)
  if (value < 1000) return `${Math.round(value)}ms`
  if (value < 60000) return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}s`
  return `${(value / 60000).toFixed(1)}min`
}

export function evaluationHealth(evaluation = {}) {
  const deterministicPassed = Number(evaluation.deterministicPassed) || 0
  const deterministicCases = Number(evaluation.deterministicCases) || 0
  const realPassed = Number(evaluation.realPassed) || 0
  const realExecuted = Number(evaluation.realExecuted) || 0
  const deterministicHealthy = deterministicCases > 0 && deterministicPassed === deterministicCases
  const realHealthy = realExecuted === 0 || realPassed === realExecuted
  return {
    healthy: deterministicHealthy && realHealthy,
    deterministic: `${deterministicPassed}/${deterministicCases}`,
    real: realExecuted ? `${realPassed}/${realExecuted}` : '未配置',
    realSkipped: Number(evaluation.realSkipped) || 0,
  }
}

export function workflowMetricRows(metrics = {}) {
  return (metrics.workflows || []).map(item => {
    const completed = Number(item.completed) || 0
    const failed = Number(item.failed) || 0
    const cancelled = Number(item.cancelled) || 0
    return {
      workflow: item.workflow,
      label: WORKFLOW_LABELS[item.workflow] || item.workflow,
      total: Number(item.total) || 0,
      terminal: completed + failed + cancelled,
      completed,
      completionRate: Number(item.completionRate) || 0,
      repairRate: Number(item.repairRate) || 0,
      averageLatency: formatMetricDuration(item.averageLatencyMs),
      averageTokens: Math.round(Number(item.averageTokens) || 0),
      averageEvidence: Number(item.averageEvidence) || 0,
    }
  })
}
