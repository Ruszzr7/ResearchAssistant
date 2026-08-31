import api from './index'

export function getPaperMemoryStatus(paperId) {
  return api.get(`/papers/${paperId}/readiness`).then(response => response.data)
}

export function startPaperUnderstanding(paperId, idempotencyKey) {
  return api.post(`/papers/${paperId}/memory/understand`, null, {
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
  }).then(response => response.data)
}
