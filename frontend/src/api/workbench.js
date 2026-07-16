import api from './index'

export function resolveSelectionAnchor(paperId, selection) {
  return api.post(`/papers/${paperId}/workbench/selection-anchor`, selection).then(r => r.data)
}

export function recognizeFormulaRegion(paperId, request) {
  return api.post(`/papers/${paperId}/workbench/formula-regions/recognize`, request).then(r => r.data)
}

export function confirmFormulaRegion(paperId, regionId, latex) {
  return api.put(`/papers/${paperId}/workbench/formula-regions/${regionId}/confirm`, { latex })
    .then(r => r.data)
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

export function getTranslationStatus() {
  return api.get('/translations/status').then(r => r.data)
}

export function translateTexts(request, config = {}) {
  return api.post('/translations', request, config).then(r => r.data)
}
