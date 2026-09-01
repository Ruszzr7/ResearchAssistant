import api from './index.js'

export function listResearchSessions(params = {}) {
  return api.get('/research/sessions', { params }).then(response => response.data)
}

export function createResearchSession(request) {
  return api.post('/research/sessions', request).then(response => response.data)
}

export function getResearchSession(sessionId) {
  return api.get(`/research/sessions/${sessionId}`).then(response => response.data)
}

export function updateResearchSession(sessionId, request) {
  return api.put(`/research/sessions/${sessionId}`, request).then(response => response.data)
}

export function deleteResearchSession(sessionId) {
  return api.delete(`/research/sessions/${sessionId}`)
}
