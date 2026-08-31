export const PAPER_AGENT_MODE = 'agent'

export function buildAgentTurnRequest({
  paperId,
  userMessage = '',
  selectionAnchor = null,
  attachments = [],
}) {
  const normalizedPaperId = Number(paperId)
  if (!Number.isInteger(normalizedPaperId) || normalizedPaperId <= 0) throw new Error('当前论文无效')
  const normalizedMessage = String(userMessage || '').trim()
  if (!normalizedMessage) throw new Error('请输入问题')
  const normalizedAttachments = (attachments || []).slice(0, 3).map(item => ({
    name: String(item?.name || '').slice(0, 160),
    mimeType: String(item?.mimeType || 'application/octet-stream').slice(0, 120),
    content: String(item?.content || '').slice(0, 12_000),
    truncated: Boolean(item?.truncated),
    rawFile: item?.rawFile || null,
    kind: item?.mimeType === 'application/x-latex' ? 'FORMULA_TEXT' : 'FILE',
  })).filter(item => item.name && (item.content || item.rawFile))
  return {
    paperId: normalizedPaperId,
    userMessage: normalizedMessage,
    selectionAnchor,
    attachments: normalizedAttachments,
  }
}
