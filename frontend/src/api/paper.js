import api from './index'

export function listPapers(params = {}) {
  return api.get('/papers', {
    params: {
      page: 1,
      size: 1000,
      ...params,
    },
  }).then(r => r.data.records)
}

export function getPaper(id) {
  return api.get(`/papers/${id}`)
}
