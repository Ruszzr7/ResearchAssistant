import api from './index.js'

export function executeAgentTurn(input, config = {}) {
  return api.post('/agent/turns', input, config).then(response => response.data)
}

export function getAgentRun(runId, config = {}) {
  return api.get(`/agent/turns/runs/${runId}`, config).then(response => response.data)
}

export function cancelAgentRun(runId) {
  return api.post(`/agent/turns/runs/${runId}/cancel`).then(response => response.data)
}

export function uploadAgentAttachment(conversationId, attachment) {
  const form = new FormData()
  const mediaType = attachment.mimeType || 'text/plain'
  // Always upload the original bytes for binary attachments. Rebuilding a PDF
  // or image from a truncated preview silently destroys the information the
  // document model is meant to understand.
  const file = attachment.rawFile
    ? new File([attachment.rawFile], attachment.name, { type: mediaType })
    : new File([attachment.content || ''], attachment.name, { type: mediaType })
  form.append('conversationId', String(conversationId))
  form.append('kind', attachment.kind || 'FILE')
  form.append('file', file, attachment.name)
  return api.post('/agent/attachments', form).then(response => response.data)
}

export function submitAgentActionReceipt(request) {
  return api.post('/agent/turns/actions/receipt', request).then(response => response.data)
}

export function renewAgentActionTicket(runId, toolCallId) {
  return api.post(`/agent/turns/runs/${runId}/actions/${toolCallId}/ticket`).then(response => response.data)
}
