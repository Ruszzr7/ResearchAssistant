import { MAX_CHAT_ATTACHMENTS } from '@/utils/chatAttachments.js'

export const PAPER_AGENT_MODE = 'agent'
export const MAX_AGENT_MESSAGE_CHARACTERS = 2_000
export const MAX_TEXT_SELECTION_CHARACTERS = 4_000
export const MAX_FORMULA_SELECTION_CHARACTERS = 2_000

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
  if (normalizedMessage.length > MAX_AGENT_MESSAGE_CHARACTERS) throw new Error('输入内容过长')
  if ((attachments || []).length > MAX_CHAT_ATTACHMENTS) throw new Error('每条消息最多添加 2 个附件')
  const selectedText = String(selectionAnchor?.targetText || selectionAnchor?.text || '')
  const selectionLimit = String(selectionAnchor?.contentType || '').toUpperCase().includes('FORMULA')
    ? MAX_FORMULA_SELECTION_CHARACTERS
    : MAX_TEXT_SELECTION_CHARACTERS
  if (selectedText.length > selectionLimit) throw new Error('选取内容过长')
  const normalizedAttachments = (attachments || []).map(item => ({
    name: String(item?.name || '').slice(0, 160),
    mimeType: String(item?.mimeType || 'application/octet-stream').slice(0, 120),
    content: String(item?.content || ''),
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
