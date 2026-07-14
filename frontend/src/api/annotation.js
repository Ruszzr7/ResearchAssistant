import api from './index'

export function listAnnotations(paperId) {
  return api.get(`/papers/${paperId}/annotations`).then(r => r.data)
}

export function createAnnotation(paperId, annotation) {
  return api.post(`/papers/${paperId}/annotations`, annotation).then(r => r.data)
}

export function updateAnnotation(paperId, annotationId, annotation) {
  return api.put(`/papers/${paperId}/annotations/${annotationId}`, annotation).then(r => r.data)
}

export function deleteAnnotation(paperId, annotationId) {
  return api.delete(`/papers/${paperId}/annotations/${annotationId}`)
}

export function generateAiAnnotations(paperId) {
  return api.post(`/papers/${paperId}/annotations/ai-generate`).then(r => r.data)
}
