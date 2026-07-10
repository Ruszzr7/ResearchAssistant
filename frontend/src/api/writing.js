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

export function generateOutline(data) {
  return api.post('/writing/outline', data)
}

export function generateRelatedWork(data) {
  return api.post('/writing/related-work', data)
}

export function checkCitations(data) {
  return api.post('/writing/citation-check', data)
}
