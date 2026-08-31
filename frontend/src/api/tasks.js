import api from '@/api/index.js'

/** Centralized task API so views do not duplicate transport details. */
export function listTasks(limit = 100) {
  return api.get('/research-automation/tasks', { params: { limit } })
}

export function getTask(taskId, config = {}) {
  return api.get(`/research-automation/task/${taskId}`, config)
}

export function cancelTask(taskId) {
  return api.post(`/research-automation/task/${taskId}/cancel`)
}

export function retryWorkflowTask(taskId) {
  return api.post(`/research-automation/workflow/${taskId}/retry`)
}

export function deleteTask(taskId) {
  return api.delete(`/research-automation/task/${taskId}`)
}

export function confirmWorkflowTask(taskId, payload) {
  return api.post(`/research-automation/workflow/${taskId}/confirm`, payload)
}
