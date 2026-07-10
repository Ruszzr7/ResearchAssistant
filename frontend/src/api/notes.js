import api from './index'

export function listNotesByPaper(paperId) {
  return api.get(`/papers/${paperId}/notes`).then(r => r.data)
}

export function createNote(paperId, note) {
  return api.post(`/papers/${paperId}/notes`, note).then(r => r.data)
}

export function updateNote(noteId, note) {
  return api.put(`/notes/${noteId}`, note).then(r => r.data)
}

export function deleteNote(noteId) {
  return api.delete(`/notes/${noteId}`)
}

export function unlinkNote(paperId, noteId) {
  return api.delete(`/papers/${paperId}/notes/${noteId}`)
}

export function listPaperIdsByNote(noteId) {
  return api.get(`/notes/${noteId}/papers`).then(r => r.data)
}
