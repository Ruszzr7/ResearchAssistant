import api from './index'

export function resolveSelectionAnchor(paperId, selection) {
  return api.post(`/papers/${paperId}/workbench/selection-anchor`, selection).then(r => r.data)
}

export function retrieveLocalEvidence(paperId, anchor, query = '', maxResults = 6) {
  return api.post(`/papers/${paperId}/workbench/evidence/local`, {
    anchor,
    query,
    maxResults
  }).then(r => r.data)
}
