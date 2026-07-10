import api from './index'

export function listReadingPlans() {
  return api.get('/reading-plans')
}

export function getReadingPlan(id) {
  return api.get(`/reading-plans/${id}`)
}

export function createReadingPlan(data) {
  return api.post('/reading-plans', data)
}

export function updateReadingPlan(id, data) {
  return api.put(`/reading-plans/${id}`, data)
}

export function deleteReadingPlan(id) {
  return api.delete(`/reading-plans/${id}`)
}

export function addPlanItem(planId, data) {
  return api.post(`/reading-plans/${planId}/items`, data)
}

export function updatePlanItem(planId, itemId, data) {
  return api.put(`/reading-plans/${planId}/items/${itemId}`, data)
}

export function deletePlanItem(planId, itemId) {
  return api.delete(`/reading-plans/${planId}/items/${itemId}`)
}

export function getWeeklyReading() {
  return api.get('/reading-plans/weekly')
}

export function getReminders() {
  return api.get('/reading-plans/reminders')
}
