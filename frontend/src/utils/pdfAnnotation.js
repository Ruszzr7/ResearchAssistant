import { cloneSelectionTextAnchor } from '@/utils/pdfSelectionAnchor.js'

const MINIMUM_RANGE_WIDTH = 0.006
const MARKER_TYPES = new Set(['NOTE', 'COMMENT'])

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

/**
 * Convert a persisted annotation point to the current PDF viewport.
 *
 * Agent annotations use PDF_NORMALIZED, whose origin is the page's upper-left
 * corner (the same contract as NormalizedBoundingBox).  PDF.js conversion
 * points use the PDF lower-left origin, so the normalized y value must be
 * inverted exactly once before conversion.  User selections use viewport
 * normalized coordinates and do not need that conversion.
 */
export function annotationPointForPage(x, y, page, coords = {}) {
  const viewport = page?.viewport
  if (!viewport) return [0, 0]
  const normalizedX = Math.max(0, Math.min(1, Number(x) || 0))
  const normalizedY = Math.max(0, Math.min(1, Number(y) || 0))
  if (coords?.coordinateSpace === 'viewport') {
    return [normalizedX * viewport.width, normalizedY * viewport.height]
  }
  const viewBox = viewport.viewBox || [0, 0, viewport.width, viewport.height]
  const width = Number(coords?.pageWidth) || (viewBox[2] - viewBox[0])
  const height = Number(coords?.pageHeight) || (viewBox[3] - viewBox[1])
  if (coords?.coordinateSpace === 'PDF_NORMALIZED') {
    return viewport.convertToViewportPoint(
      normalizedX * width + viewBox[0],
      viewBox[3] - normalizedY * height,
    )
  }
  return viewport.convertToViewportPoint(
    normalizedX * width + viewBox[0],
    normalizedY * height + viewBox[1],
  )
}

/**
 * Return the visually lower edge of a text quad for an underline.
 *
 * Both old and new persisted Agent quads are accepted: selecting the edge by
 * its rendered y position avoids depending on their historical point order.
 */
export function quadLineForPage(quad, page, coords = {}) {
  if (!quad || !page?.viewport) return { x1: 0, y1: 0, x2: 0, y2: 0 }
  const points = [
    annotationPointForPage(quad.x1, quad.y1, page, coords),
    annotationPointForPage(quad.x2, quad.y2, page, coords),
    annotationPointForPage(quad.x3, quad.y3, page, coords),
    annotationPointForPage(quad.x4, quad.y4, page, coords),
  ]
  const first = [points[0], points[1]]
  const second = [points[2], points[3]]
  const averageY = edge => (edge[0][1] + edge[1][1]) / 2
  const edge = averageY(first) >= averageY(second) ? first : second
  const ordered = [...edge].sort((left, right) => left[0] - right[0])
  return { x1: ordered[0][0], y1: ordered[0][1], x2: ordered[1][0], y2: ordered[1][1] }
}

/**
 * Return one underline for a complete formula region.
 *
 * A numbered display formula may contain several physical rectangles (for
 * example a numerator and a denominator on different rows).  Underlining each
 * rectangle separately puts a line through the equation.  The formula region
 * is therefore reduced to one horizontal line below the lowest rendered point.
 */
export function formulaUnderlineLineForPage(quads, page, coords = {}) {
  if (!Array.isArray(quads) || !quads.length || !page?.viewport) {
    return { x1: 0, y1: 0, x2: 0, y2: 0 }
  }
  const points = quads.flatMap(quad => [
    annotationPointForPage(quad?.x1, quad?.y1, page, coords),
    annotationPointForPage(quad?.x2, quad?.y2, page, coords),
    annotationPointForPage(quad?.x3, quad?.y3, page, coords),
    annotationPointForPage(quad?.x4, quad?.y4, page, coords),
  ])
  const valid = points.filter(point => point.every(Number.isFinite))
  if (!valid.length) return { x1: 0, y1: 0, x2: 0, y2: 0 }
  const left = Math.min(...valid.map(([x]) => x))
  const right = Math.max(...valid.map(([x]) => x))
  const bottom = Math.max(...valid.map(([, y]) => y))
  // PDF formula quads describe the extracted glyph boxes, whose lowest edge
  // can be flush with a fraction denominator or a radical.  Keep the line
  // below the complete region with a small viewport-space gap; this is only
  // for the visual underline and does not alter the stored formula geometry.
  const heights = quads.flatMap(quad => {
    const ys = [quad?.y1, quad?.y2, quad?.y3, quad?.y4]
      .map(value => Number(value)).filter(Number.isFinite)
    return ys.length ? Math.max(...ys) - Math.min(...ys) : []
  }).filter(height => height > 0)
  const viewportHeight = Number(page.viewport.height)
  const gap = Math.max(2, Math.min(6, (Math.max(...heights, 0) * (viewportHeight || 0)) * 0.18))
  const lineY = Number.isFinite(viewportHeight)
    ? Math.min(viewportHeight, bottom + gap) : bottom + gap
  return { x1: left, y1: lineY, x2: right, y2: lineY }
}

function quadBounds(quad) {
  if (!quad) return null
  const xs = [quad.x1, quad.x2, quad.x3, quad.x4].map(value => Number(value))
  const ys = [quad.y1, quad.y2, quad.y3, quad.y4].map(value => Number(value))
  if (xs.some(value => !Number.isFinite(value)) || ys.some(value => !Number.isFinite(value))) return null
  const x = Math.min(...xs)
  const right = Math.max(...xs)
  const y = Math.min(...ys)
  const bottom = Math.max(...ys)
  return right > x && bottom > y ? { x, right, y, bottom, width: right - x, height: bottom - y } : null
}

function lineOverlap(first, second) {
  const overlap = Math.max(0, Math.min(first.bottom, second.bottom) - Math.max(first.y, second.y))
  return overlap / Math.max(MINIMUM_RANGE_WIDTH, Math.min(first.height, second.height))
}

function lineHorizontalGap(first, second) {
  if (first.right >= second.x && second.right >= first.x) return 0
  return first.right < second.x ? second.x - first.right : first.x - second.right
}

function visualLines(quads) {
  const records = quads.map((quad, index) => ({ quad, index, box: quadBounds(quad) }))
    .filter(record => record.box)
    .sort((first, second) => first.box.y - second.box.y || first.box.x - second.box.x || first.index - second.index)
  const lines = []
  for (const record of records) {
    const previous = lines[lines.length - 1]
    const horizontalLimit = previous
      // A normal word gap is only a few glyph heights; a two-column gutter is
      // materially wider. Keep the cap below that gutter so a same-y pointer
      // cannot make the fallback treat both columns as one visual line.
      ? Math.max(.012, Math.min(.032, Math.max(previous.box.height, record.box.height) * 2))
      : 0
    const sameVisualLine = previous && lineHorizontalGap(previous.box, record.box) <= horizontalLimit
      && (lineOverlap(previous.box, record.box) >= 0.45
      || Math.abs((previous.box.y + previous.box.bottom) - (record.box.y + record.box.bottom))
        <= Math.max(previous.box.height, record.box.height) * 0.7)
    if (sameVisualLine) {
      previous.records.push(record)
      previous.box = {
        x: Math.min(previous.box.x, record.box.x),
        right: Math.max(previous.box.right, record.box.right),
        y: Math.min(previous.box.y, record.box.y),
        bottom: Math.max(previous.box.bottom, record.box.bottom),
        width: Math.max(previous.box.right, record.box.right) - Math.min(previous.box.x, record.box.x),
        height: Math.max(previous.box.bottom, record.box.bottom) - Math.min(previous.box.y, record.box.y),
      }
    } else {
      lines.push({ records: [record], box: { ...record.box } })
    }
  }
  return lines
}

function horizontalDistance(box, x) {
  if (x < box.x) return box.x - x
  if (x > box.right) return x - box.right
  return 0
}

function closestLine(lines, pointerX, pointerY, fallbackIndex) {
  if (!lines.length) return null
  if (!Number.isFinite(pointerY)) return lines[Math.max(0, Math.min(lines.length - 1, fallbackIndex))]
  const containing = lines.filter(line => pointerY >= line.box.y && pointerY <= line.box.bottom)
  const candidates = containing.length ? containing : lines
  return candidates.reduce((best, line, index) => {
    const verticalDistance = pointerY < line.box.y
      ? line.box.y - pointerY
      : pointerY > line.box.bottom ? pointerY - line.box.bottom : 0
    const score = verticalDistance * 100 + horizontalDistance(line.box, pointerX)
    if (!best || score < best.score) return { line, index, score }
    return best
  }, null).line
}

function lineIndex(lines, line) {
  return Math.max(0, lines.indexOf(line))
}

function setQuadLeft(quad, value) {
  quad.x1 = value
  quad.x4 = value
}

function setQuadRight(quad, value) {
  quad.x2 = value
  quad.x3 = value
}

function cloneLineGroups(lines, start, end) {
  return lines.slice(start, end).map(line => ({
    records: line.records.map(record => ({
      quad: { ...record.quad },
      box: { ...record.box },
    })),
  }))
}

function trimLineStart(line, pointerX) {
  const records = line.records
    .filter(record => record.box.right > pointerX + MINIMUM_RANGE_WIDTH / 2)
    .map(record => ({ quad: { ...record.quad }, box: { ...record.box } }))
  if (!records.length) {
    const last = line.records[line.records.length - 1]
    const quad = { ...last.quad }
    const right = last.box.right
    setQuadLeft(quad, Math.max(last.box.x, right - MINIMUM_RANGE_WIDTH))
    return [{ quad, box: quadBounds(quad) }]
  }
  const first = records[0]
  const nextLeft = Math.min(pointerX, first.box.right - MINIMUM_RANGE_WIDTH)
  setQuadLeft(first.quad, Math.max(first.box.x, nextLeft))
  first.box = quadBounds(first.quad)
  return records
}

function trimLineEnd(line, pointerX) {
  const records = line.records
    .filter(record => record.box.x < pointerX - MINIMUM_RANGE_WIDTH / 2)
    .map(record => ({ quad: { ...record.quad }, box: { ...record.box } }))
  if (!records.length) {
    const first = line.records[0]
    const quad = { ...first.quad }
    setQuadRight(quad, Math.min(first.box.right, first.box.x + MINIMUM_RANGE_WIDTH))
    return [{ quad, box: quadBounds(quad) }]
  }
  const last = records[records.length - 1]
  const nextRight = Math.max(pointerX, last.box.x + MINIMUM_RANGE_WIDTH)
  setQuadRight(last.quad, Math.min(last.box.right, nextRight))
  last.box = quadBounds(last.quad)
  return records
}

/**
 * 调整文本批注的首端或末端矩形。
 *
 * pointerY 用于选择换行后的目标行，pointerX 始终被限制在该行的物理范围内，
 * 因而不会把双栏页面中的端点拖到另一栏。具备 PDFium 字符锚点的批注由组件
 * 直接重新执行真实文本选择；本函数是旧批注或没有字符锚点时的安全几何后备。
 */
export function resizeTextAnnotationQuads(quads, edge, pointerX, pointerY = null) {
  if (!Array.isArray(quads) || quads.length === 0 || !['start', 'end'].includes(edge)) {
    return { quads, changed: false }
  }

  const lines = visualLines(quads)
  if (!lines.length) return { quads, changed: false }
  const fallbackIndex = edge === 'start' ? 0 : lines.length - 1
  const normalizedPointerX = clamp(Number(pointerX), 0.002, 0.998)
  const pointerYNumber = pointerY == null ? Number.NaN : Number(pointerY)
  const normalizedPointerY = Number.isFinite(pointerYNumber)
    ? clamp(pointerYNumber, 0.002, 0.998) : Number.NaN
  const targetLine = closestLine(lines, normalizedPointerX, normalizedPointerY, fallbackIndex)
  const targetIndex = lineIndex(lines, targetLine)
  const trimmed = edge === 'start'
    ? trimLineStart(targetLine, clamp(normalizedPointerX, targetLine.box.x,
      targetLine.box.right - MINIMUM_RANGE_WIDTH))
    : trimLineEnd(targetLine, clamp(normalizedPointerX, targetLine.box.x + MINIMUM_RANGE_WIDTH,
      targetLine.box.right))
  const lineRecords = edge === 'start'
    ? [{ records: trimmed }, ...cloneLineGroups(lines, targetIndex + 1, lines.length)]
    : [...cloneLineGroups(lines, 0, targetIndex), { records: trimmed }]
  const nextQuads = lineRecords.flatMap(line => line.records.map(record => record.quad))
  const changed = JSON.stringify(nextQuads) !== JSON.stringify(quads)
  return changed ? { quads: nextQuads, changed: true } : { quads, changed: false }
}

function buildSelectionMarkerDraft(type, { localId, paperId, color, selection, notePosition }) {
  const group = selection?.groups?.[0]
  const viewport = group?.pageState?.viewport
  if (!viewport || !Array.isArray(group.quads) || group.quads.length === 0) return null

  const quads = group.quads.map(quad => ({ ...quad }))
  return {
    localId,
    paperId,
    type,
    page: group.pageNum,
    color,
    note: '',
    completed: false,
    coordinates: {
      coordinateSpace: 'viewport',
      pageWidth: viewport.width,
      pageHeight: viewport.height,
      rotation: viewport.rotation,
      scale: viewport.scale,
      quads,
      anchorQuads: quads.map(quad => ({ ...quad })),
      notePosition,
      anchorKind: 'SELECTION',
      anchorText: String(selection.text || '').slice(0, 500),
      textAnchor: cloneSelectionTextAnchor(selection.textAnchor),
    },
  }
}

/**
 * 把当前 PDF 文本选区转换为用户笔记草稿。
 */
export function buildSelectionNoteDraft(options) {
  return buildSelectionMarkerDraft('NOTE', options)
}

/**
 * 把当前 PDF 文本选区转换为批注草稿。批注与笔记共享稳定的文字锚点，
 * 但批注只在批注列表打开时展示，并拥有独立的完成状态。
 */
export function buildSelectionCommentDraft(options) {
  return buildSelectionMarkerDraft('COMMENT', options)
}

export function isMarkerAnnotation(annotation) {
  return MARKER_TYPES.has(annotation?.type)
}

export function isSelectionNote(annotation) {
  return annotation?.type === 'NOTE'
}

export function isCommentAnnotation(annotation) {
  return annotation?.type === 'COMMENT'
}

export function annotationDisplayColor(annotation) {
  return isCommentAnnotation(annotation) && annotation?.completed
    ? '#4caf50'
    : annotation?.color || '#f44336'
}
