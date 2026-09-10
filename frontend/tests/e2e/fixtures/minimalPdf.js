/**
 * 阶段 0 的固定最小 PDF 样本。
 *
 * 样本只包含一页、两行正文和一个带打印编号的公式，第二行故意属于同一条
 * 证据，便于验证“完整证据文本 + 多个物理框”不会在页面展示或跳转时丢失，
 * 同时覆盖公式编号定位。
 */
export const PHASE0_PAPER_TEXT = [
  'Phase 0 evidence baseline keeps the complete source sentence on the page.',
  'The second physical line remains part of the same evidence unit.',
]

export const PHASE0_FORMULA_TEXT = 'x = alpha + beta        (1)'

export const PHASE0_PAPER_BOXES = [
  { x: 0.08, y: 0.065, width: 0.78, height: 0.035 },
  { x: 0.08, y: 0.115, width: 0.72, height: 0.035 },
]

export const PHASE0_FORMULA_BOXES = [
  { x: 0.08, y: 0.165, width: 0.43, height: 0.035 },
]

export const PHASE0_FORMULA_FOCUS_BOXES = [
  { x: 0.39, y: 0.165, width: 0.08, height: 0.035 },
]

function pdfEscape(value) {
  return String(value).replace(/([\\()])/g, '\\$1')
}

/** Return a valid one-page PDF as a Buffer without writing to the workspace. */
export function createMinimalPdf() {
  const stream = [
    'BT',
    '/F1 12 Tf',
    '50 735 Td',
    `(${pdfEscape(PHASE0_PAPER_TEXT[0])}) Tj`,
    '0 -38 Td',
    `(${pdfEscape(PHASE0_PAPER_TEXT[1])}) Tj`,
    '0 -38 Td',
    `(${pdfEscape(PHASE0_FORMULA_TEXT)}) Tj`,
    'ET',
    '',
  ].join('\n')

  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] '
      + '/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    `<< /Length ${Buffer.byteLength(stream, 'binary')} >>\nstream\n${stream}endstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]

  let body = '%PDF-1.4\n%\xE2\xE3\xCF\xD3\n'
  const offsets = [0]
  objects.forEach((object, index) => {
    offsets[index + 1] = Buffer.byteLength(body, 'binary')
    body += `${index + 1} 0 obj\n${object}\nendobj\n`
  })
  const xrefOffset = Buffer.byteLength(body, 'binary')
  body += `xref\n0 ${objects.length + 1}\n`
  body += '0000000000 65535 f \n'
  offsets.slice(1).forEach(offset => {
    body += `${String(offset).padStart(10, '0')} 00000 n \n`
  })
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`
  body += `startxref\n${xrefOffset}\n%%EOF\n`
  return Buffer.from(body, 'binary')
}
