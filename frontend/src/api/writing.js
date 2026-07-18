import api from './index'

export function listWritingProjects() {
  return api.get('/writing/projects')
}

export function getWritingProject(id) {
  return api.get('/writing/projects/' + id)
}

export function createWritingProject(data) {
  return api.post('/writing/projects', data)
}

export function updateWritingProject(id, data) {
  return api.put('/writing/projects/' + id, data)
}

export function deleteWritingProject(id) {
  return api.delete('/writing/projects/' + id)
}

export function addProjectPaper(id, paperId) {
  return api.post('/writing/projects/' + id + '/papers', { paperId })
}

export function removeProjectPaper(id, paperId) {
  return api.delete('/writing/projects/' + id + '/papers/' + paperId)
}

export function listProjectNotes(id) {
  return api.get('/writing/projects/' + id + '/notes')
}

export function listWritingClaims(id) {
  return api.get('/writing/projects/' + id + '/claims')
}

export function createWritingClaim(id, data) {
  return api.post('/writing/projects/' + id + '/claims', data)
}

export function updateWritingClaim(claimId, data) {
  return api.put('/writing/claims/' + claimId, data)
}

export function deleteWritingClaim(claimId) {
  return api.delete('/writing/claims/' + claimId)
}

export function createWritingEvidence(claimId, data) {
  return api.post('/writing/claims/' + claimId + '/evidence', data)
}

export function updateWritingEvidence(evidenceId, data) {
  return api.put('/writing/evidence/' + evidenceId, data)
}

export function deleteWritingEvidence(evidenceId) {
  return api.delete('/writing/evidence/' + evidenceId)
}

export function generateOutline(data) {
  return api.post('/writing/outline', data)
}

export function generateRelatedWork(data) {
  return api.post('/writing/related-work', data)
}

export function checkCitations(data) {
  return api.post('/writing/citation-check', data)
}
