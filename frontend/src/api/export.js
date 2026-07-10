import api from './index'

export function exportSingleBibTeX(paperId) {
  return api.get(`/papers/${paperId}/export/bibtex`, { responseType: 'blob' })
}

export function exportBatchBibTeX(ids) {
  return api.post('/papers/export/bibtex', { ids }, { responseType: 'blob' })
}

export function syncObsidian(ids) {
  return api.post('/export/obsidian', { ids }).then(r => r.data)
}

export function syncZotero(ids) {
  return api.post('/export/zotero', { ids }).then(r => r.data)
}

export function downloadBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.URL.revokeObjectURL(url)
}
