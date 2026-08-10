const MAX_FILE_BYTES = 10 * 1024 * 1024
const MAX_CONTENT_CHARACTERS = 12_000
const MAX_PDF_PAGES = 25
const TEXT_EXTENSIONS = new Set([
  'txt', 'md', 'markdown', 'tex', 'csv', 'json', 'yaml', 'yml', 'xml', 'log',
])

export const CHAT_ATTACHMENT_ACCEPT = [
  '.pdf', '.txt', '.md', '.markdown', '.tex', '.csv', '.json', '.yaml', '.yml', '.xml', '.log',
].join(',')

export async function prepareChatAttachment(file) {
  if (!file?.name) throw new Error('未读取到附件')
  if (Number(file.size) > MAX_FILE_BYTES) throw new Error('单个附件不能超过 10 MB')
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  let extracted
  if (extension === 'pdf' || file.type === 'application/pdf') {
    extracted = await extractPdfText(file)
  } else if (TEXT_EXTENSIONS.has(extension) || String(file.type || '').startsWith('text/')) {
    extracted = await file.text()
  } else {
    throw new Error('当前支持 PDF、TXT、Markdown、LaTeX、CSV、JSON、YAML 和 XML')
  }
  const normalized = normalizeAttachmentText(extracted)
  if (!normalized) throw new Error(`附件「${file.name}」没有可读取的文字`)
  const truncated = normalized.length > MAX_CONTENT_CHARACTERS
  return {
    name: String(file.name).slice(0, 160),
    mimeType: String(file.type || mimeTypeFor(extension)).slice(0, 120),
    content: normalized.slice(0, MAX_CONTENT_CHARACTERS),
    truncated,
    size: Number(file.size) || 0,
  }
}

async function extractPdfText(file) {
  const [pdfjsLib, workerModule] = await Promise.all([
    import('pdfjs-dist'),
    import('pdfjs-dist/build/pdf.worker.mjs?url'),
  ])
  pdfjsLib.GlobalWorkerOptions.workerSrc = workerModule.default
  const loadingTask = pdfjsLib.getDocument({ data: await file.arrayBuffer() })
  const document = await loadingTask.promise
  const pages = []
  try {
    const limit = Math.min(document.numPages, MAX_PDF_PAGES)
    for (let pageNumber = 1; pageNumber <= limit; pageNumber += 1) {
      const page = await document.getPage(pageNumber)
      const content = await page.getTextContent()
      const text = content.items.map(item => item.str || '').join(' ')
      if (text.trim()) pages.push(`[第 ${pageNumber} 页]\n${text}`)
      if (pages.join('\n').length >= MAX_CONTENT_CHARACTERS) break
    }
  } finally {
    await loadingTask.destroy().catch(() => {})
  }
  return pages.join('\n\n')
}

function normalizeAttachmentText(value) {
  return String(value || '')
    .replace(/\u0000/g, '')
    .replace(/\r\n?/g, '\n')
    .replace(/[\t ]+/g, ' ')
    .replace(/\n{4,}/g, '\n\n\n')
    .trim()
}

function mimeTypeFor(extension) {
  return {
    md: 'text/markdown', markdown: 'text/markdown', tex: 'application/x-tex',
    csv: 'text/csv', json: 'application/json', yaml: 'application/yaml',
    yml: 'application/yaml', xml: 'application/xml', log: 'text/plain', txt: 'text/plain',
  }[extension] || 'text/plain'
}
