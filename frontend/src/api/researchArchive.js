import api from './index.js'

export function normalizeResearchSessionPage(value) {
  if (Array.isArray(value)) {
    return {
      records: value,
      total: value.length,
      current: 1,
      size: value.length || 1,
      pages: value.length ? 1 : 0,
    }
  }
  const records = Array.isArray(value?.records) ? value.records : []
  const size = Number(value?.size) > 0 ? Number(value.size) : 20
  const total = Number(value?.total) >= 0 ? Number(value.total) : records.length
  return {
    records,
    total,
    current: Number(value?.current) > 0 ? Number(value.current) : 1,
    size,
    pages: Number(value?.pages) >= 0 ? Number(value.pages) : (total ? Math.ceil(total / size) : 0),
  }
}

export function listResearchSessions(params = {}) {
  return api.get('/research/sessions', { params }).then(response => normalizeResearchSessionPage(response.data))
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
