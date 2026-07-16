import api from './index'

export function resolveSelectionAnchor(paperId, selection) {
  return api.post(`/papers/${paperId}/workbench/selection-anchor`, selection).then(r => r.data)
}

export function planWorkbenchRun(request) {
  return api.post('/workbench/runs/plan', request).then(r => r.data)
}

export function executeWorkbenchRun(runId) {
  return api.post(`/workbench/runs/${runId}/execute`).then(r => r.data)
}

export function getWorkbenchRun(runId, config = {}) {
  return api.get(`/workbench/runs/${runId}`, config).then(r => r.data)
}

export function listPaperWorkbenchRuns(paperId, limit = 5) {
  return api.get('/workbench/runs', { params: { paperId, limit } }).then(r => r.data)
}

export function getWorkbenchMetrics(days = 30) {
  return api.get('/workbench/metrics', { params: { days } }).then(r => r.data)
}
