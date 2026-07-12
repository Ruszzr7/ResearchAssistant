import api from '@/api/index.js'

/** Centralized task API so views do not duplicate transport details. */
export function listTasks(limit = 100) {
  return api.get('/agent/tasks', { params: { limit } })
}

export function getTask(taskId, config = {}) {
  return api.get(`/agent/task/${taskId}`, config)
}

export function cancelTask(taskId) {
  return api.post(`/agent/task/${taskId}/cancel`)
}

export function retryWorkflowTask(taskId) {
  return api.post(`/agent/workflow/${taskId}/retry`)
}

export function deleteTask(taskId) {
  return api.delete(`/agent/task/${taskId}`)
}

export function confirmWorkflowTask(taskId, payload) {
  return api.post(`/agent/workflow/${taskId}/confirm`, payload)
}
