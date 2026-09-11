import { mapAgentEvidenceList } from './evidenceViewModel.js'

/**
 * 将持久化的研究消息还原为论文助手实时使用的消息形状。
 * 历史版本只保存 citations，因此这里按同一 answerStart/answerEnd 契约补出 claims，
 * 证据本体和所有 locator 仍交给共享 evidenceViewModel 处理。
 */
export function mapResearchMessageView(message = {}) {
  const rawEvidence = message?.evidence
  const payload = Array.isArray(rawEvidence)
    ? { evidence: rawEvidence }
    : rawEvidence && typeof rawEvidence === 'object' ? rawEvidence : {}
  const content = String(message?.content || '')
  const claims = Array.isArray(payload.claims)
    ? payload.claims
    : (Array.isArray(payload.citations) ? payload.citations.map(citation => ({
      text: content.slice(Number(citation?.answerStart) || 0, Number(citation?.answerEnd) || 0),
      evidenceIds: citation?.sourceObjectId ? [citation.sourceObjectId] : [],
    })) : [])

  return {
    ...message,
    id: message?.messageKey || String(message?.id || `${message?.role || 'message'}-${message?.createdAt || ''}`),
    runId: message?.runId || null,
    role: String(message?.role || '').toUpperCase() === 'USER' ? 'user' : 'assistant',
    content,
    claims,
    answerBlocks: Array.isArray(payload.answerBlocks) ? payload.answerBlocks : [],
    evidence: mapAgentEvidenceList(payload.evidence || []),
    regionFallback: Boolean(payload.regionFallback),
    actions: payload.actions || payload.pendingActions || [],
    selectionAnchor: message?.selectionAnchor || null,
    contextInherited: Boolean(payload.contextInherited),
    contextMode: payload.contextMode || '',
    attachments: Array.isArray(payload.attachments) ? payload.attachments : [],
    messageStatus: message?.messageStatus || 'FINAL',
    messageType: message?.messageType || 'CHAT',
    evidenceSchemaVersion: message?.evidenceSchemaVersion || null,
  }
}

export function mapResearchMessageList(messages = []) {
  return (Array.isArray(messages) ? messages : []).map(mapResearchMessageView)
}
