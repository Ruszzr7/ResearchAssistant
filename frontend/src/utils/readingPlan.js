export const READING_OUTPUT_OPTIONS = Object.freeze([
  { value: 'SUMMARY', label: '核心结论摘要' },
  { value: 'METHOD_MAP', label: '方法与假设图谱' },
  { value: 'RESULT_CHECK', label: '实验结果核验' },
  { value: 'IMPROVEMENT', label: '局限与研究切入点' },
  { value: 'COMPARISON', label: '跨论文对比证据' },
])

const outputLabels = new Map(READING_OUTPUT_OPTIONS.map(item => [item.value, item.label]))

export function outputLabel(value) {
  return outputLabels.get(value) || '核心结论摘要'
}

export function planCompletion(done, total) {
  const safeTotal = Math.max(0, Number(total) || 0)
  if (!safeTotal) return 0
  return Math.round(Math.min(Math.max(Number(done) || 0, 0), safeTotal) / safeTotal * 100)
}
