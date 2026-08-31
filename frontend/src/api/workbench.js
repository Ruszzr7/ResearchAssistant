import api from './index'

export function resolveSelectionAnchor(paperId, selection) {
  return api.post(`/papers/${paperId}/workbench/selection-anchor`, selection).then(r => r.data)
}

export function recognizeFormulaRegion(paperId, request) {
  return api.post(`/papers/${paperId}/workbench/formula-regions/recognize`, request).then(r => r.data)
}

export function confirmFormulaRegion(paperId, regionId, formulas) {
  return api.put(`/papers/${paperId}/workbench/formula-regions/${regionId}/confirm`, { formulas })
    .then(r => r.data)
}

export function getTranslationStatus() {
  return api.get('/translations/status').then(r => r.data)
}

export function translateTexts(request, config = {}) {
  return api.post('/translations', request, config).then(r => r.data)
}
