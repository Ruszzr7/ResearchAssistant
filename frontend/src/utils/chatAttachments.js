const MAX_FILE_BYTES = 20 * 1024 * 1024

const MIME_BY_EXTENSION = Object.freeze({
  pdf: 'application/pdf',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  txt: 'text/plain',
  md: 'text/markdown',
  markdown: 'text/markdown',
  tex: 'application/x-tex',
  csv: 'text/csv',
  json: 'application/json',
  yaml: 'application/yaml',
  yml: 'application/yaml',
  xml: 'application/xml',
  log: 'text/plain',
})

const SUPPORTED_MIME_TYPES = new Set(Object.values(MIME_BY_EXTENSION))
const TEXT_EXTENSIONS = new Set(['txt', 'md', 'markdown', 'tex', 'csv', 'json', 'yaml', 'yml', 'xml', 'log'])
const MAX_TEXT_CHARACTERS = 12_000

export const CHAT_ATTACHMENT_ACCEPT = Object.keys(MIME_BY_EXTENSION)
  .map(extension => `.${extension}`).join(',')

/**
 * Keep the original bytes. PDF/Word/image understanding belongs to the
 * configured document API; extracting text in the browser loses formulas,
 * layout and figures. The backend receives the original file and chooses the
 * native or image fallback supported by that API.
 */
export async function prepareChatAttachment(file) {
  if (!file?.name) throw new Error('未读取到附件')
  if (Number(file.size) > MAX_FILE_BYTES) throw new Error('单个附件不能超过 20 MB')
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  const inferredMimeType = MIME_BY_EXTENSION[extension]
  const suppliedMimeType = String(file.type || '').toLowerCase().split(';')[0].trim()
  const mimeType = inferredMimeType || suppliedMimeType
  if (!mimeType || !SUPPORTED_MIME_TYPES.has(mimeType)) {
    throw new Error('当前支持 PDF、Word、JPG、PNG，以及常用文本附件')
  }
  let content = ''
  let truncated = false
  if (TEXT_EXTENSIONS.has(extension) && typeof file.text === 'function') {
    const text = String(await file.text())
      .replace(/\u0000/g, '')
      .replace(/\r\n?/g, '\n')
      .replace(/[\t ]+/g, ' ')
      .replace(/\n{4,}/g, '\n\n\n')
      .trim()
    truncated = text.length > MAX_TEXT_CHARACTERS
    content = text.slice(0, MAX_TEXT_CHARACTERS)
  }
  return {
    name: String(file.name).slice(0, 160),
    mimeType: mimeType.slice(0, 120),
    content,
    truncated,
    size: Number(file.size) || 0,
    rawFile: file,
  }
}
