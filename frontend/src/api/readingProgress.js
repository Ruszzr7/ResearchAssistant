import api from './index.js'

export function getReadingProgress(paperId) {
  return api.get(`/papers/${paperId}/reading-progress`)
}

export function updateReadingProgress(paperId, currentPage) {
  return api.post(`/papers/${paperId}/reading-progress`, { currentPage })
}

export function addReadingTime(paperId, seconds) {
  return api.post(`/papers/${paperId}/reading-time`, { seconds })
}
