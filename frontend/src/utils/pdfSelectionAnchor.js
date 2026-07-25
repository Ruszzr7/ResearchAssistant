const EPSILON = 1e-6
const MAX_CONTENT_SEGMENTS = 100
const MAX_SEGMENT_TEXT = 1200
const VALID_SEGMENT_TYPES = new Set(['TEXT', 'INLINE_MATH', 'DISPLAY_MATH'])
const INVALID_CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/gu

function clamp(value) {
  return Math.max(0, Math.min(1, Number(value) || 0))
}

function quadToBox(quad) {
  if (!quad) return null
  const xs = [quad.x1, quad.x2, quad.x3, quad.x4].map(clamp)
  const ys = [quad.y1, quad.y2, quad.y3, quad.y4].map(clamp)
  const x = Math.min(...xs)
  const y = Math.min(...ys)
  const right = Math.max(...xs)
  const bottom = Math.max(...ys)
  if (right - x <= EPSILON || bottom - y <= EPSILON) return null
  return { x, y, width: right - x, height: bottom - y }
}

function verticalOverlap(first, second) {
  const top = Math.max(first.y, second.y)
  const bottom = Math.min(first.y + first.height, second.y + second.height)
  return Math.max(0, bottom - top) / Math.max(EPSILON, Math.min(first.height, second.height))
}

function canMergeOnLine(first, second) {
  if (verticalOverlap(first, second) < 0.55) return false
  const gap = second.x - (first.x + first.width)
  const maxGap = Math.max(0.012, Math.max(first.height, second.height) * 1.35)
  return gap <= maxGap
}

function mergeBoxes(first, second) {
  const x = Math.min(first.x, second.x)
  const y = Math.min(first.y, second.y)
  const right = Math.max(first.x + first.width, second.x + second.width)
  const bottom = Math.max(first.y + first.height, second.y + second.height)
  return { x, y, width: right - x, height: bottom - y }
}

/**
 * Convert PDF.js viewport quads to compact top-left-origin boxes for the
 * backend. Word-level rects on the same visual line are merged so a normal
 * paragraph stays below the API's bounded box count.
 */
export function selectionQuadsToBoxes(quads, maxBoxes = 100) {
  const sorted = (Array.isArray(quads) ? quads : [])
    .map(quadToBox)
    .filter(Boolean)
    .sort((a, b) => a.y - b.y || a.x - b.x)

  const merged = []
  for (const box of sorted) {
    const previous = merged[merged.length - 1]
    if (previous && canMergeOnLine(previous, box)) {
      merged[merged.length - 1] = mergeBoxes(previous, box)
    } else {
      merged.push(box)
    }
  }
  return merged.slice(0, Math.max(1, maxBoxes))
}

export function selectionToAnchorPayload(selection) {
  const group = selection?.groups?.[0]
  if (!group?.pageNum) return null
  const boxes = selectionQuadsToBoxes(group.quads)
  if (!boxes.length) return null
  const clientTextAnchor = cloneSelectionTextAnchor(selection.textAnchor)
  return {
    page: group.pageNum,
    boxes,
    anchorText: String(selection.text || '').slice(0, 8000),
    ...(clientTextAnchor ? { clientTextAnchor } : {}),
  }
}

function boundedInteger(value, minimum, maximum, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.max(minimum, Math.min(maximum, Math.trunc(number)))
}

function normalizedSegmentRect(rect) {
  if (!rect) return null
  const x = clamp(rect.x)
  const y = clamp(rect.y)
  const right = clamp(x + Number(rect.width || 0))
  const bottom = clamp(y + Number(rect.height || 0))
  return right > x && bottom > y
    ? { x, y, width: right - x, height: bottom - y }
    : null
}

function splitContentSegment(segment) {
  const text = String(segment.text || '').replace(INVALID_CONTROL, '')
  if (!text.trim()) return []
  const span = Math.max(1, segment.charEnd - segment.charStart + 1)
  const chunks = []
  for (let offset = 0; offset < text.length; offset += MAX_SEGMENT_TEXT) {
    const chunk = text.slice(offset, offset + MAX_SEGMENT_TEXT)
    const startDelta = Math.floor(offset / text.length * span)
    const endDelta = Math.max(startDelta,
      Math.ceil((offset + chunk.length) / text.length * span) - 1)
    chunks.push({
      ...segment,
      charStart: segment.charStart + startDelta,
      charEnd: Math.min(segment.charEnd, segment.charStart + endDelta),
      text: chunk,
    })
  }
  return chunks
}

function unionRects(segments) {
  const rects = segments.map(segment => segment.rect).filter(Boolean)
  if (!rects.length) return null
  const x = Math.min(...rects.map(rect => rect.x))
  const y = Math.min(...rects.map(rect => rect.y))
  const right = Math.max(...rects.map(rect => rect.x + rect.width))
  const bottom = Math.max(...rects.map(rect => rect.y + rect.height))
  return { x, y, width: right - x, height: bottom - y }
}

function compactContentSegments(segments) {
  if (segments.length <= MAX_CONTENT_SEGMENTS) return segments
  return Array.from({ length: MAX_CONTENT_SEGMENTS }, (_, index) => {
    const start = Math.floor(index * segments.length / MAX_CONTENT_SEGMENTS)
    const end = Math.floor((index + 1) * segments.length / MAX_CONTENT_SEGMENTS)
    const bucket = segments.slice(start, Math.max(start + 1, end))
    const text = bucket.reduce((value, segment, segmentIndex) => {
      const gap = segmentIndex > 0
        && segment.charStart > bucket[segmentIndex - 1].charEnd + 1 ? ' ' : ''
      return value + gap + segment.text
    }, '')
    const types = new Set(bucket.map(segment => segment.type))
    const type = types.has('DISPLAY_MATH')
      ? 'DISPLAY_MATH'
      : (types.has('INLINE_MATH') ? 'INLINE_MATH' : 'TEXT')
    return {
      type,
      charStart: bucket[0].charStart,
      charEnd: bucket[bucket.length - 1].charEnd,
      text: text.length <= MAX_SEGMENT_TEXT
        ? text
        : `${text.slice(0, 599)} ${text.slice(-600)}`,
      fonts: [...new Set(bucket.flatMap(segment => segment.fonts))].slice(0, 8),
      rect: unionRects(bucket),
    }
  })
}

/** Keep auxiliary segments within the server contract without changing the authoritative range. */
export function normalizePdfiumContentSegments(contentSegments, charStart, charEnd) {
  const normalized = (Array.isArray(contentSegments) ? contentSegments : [])
    .map(segment => {
      const start = boundedInteger(segment?.charStart, charStart, charEnd, charStart)
      const end = boundedInteger(segment?.charEnd, start, charEnd, start)
      const requestedType = String(segment?.type || 'TEXT').toUpperCase()
      return {
        type: VALID_SEGMENT_TYPES.has(requestedType) ? requestedType : 'TEXT',
        charStart: start,
        charEnd: end,
        text: String(segment?.text || ''),
        fonts: (segment?.fonts || []).map(font => String(font).slice(0, 128))
          .filter(Boolean).slice(0, 8),
        rect: normalizedSegmentRect(segment?.rect),
      }
    })
    .flatMap(splitContentSegment)
    .sort((left, right) => left.charStart - right.charStart || left.charEnd - right.charEnd)
  return compactContentSegments(normalized)
}

/** Clone the active engine anchor beside normalized geometry. */
export function cloneSelectionTextAnchor(textAnchor) {
  if (!textAnchor) return null
  if (textAnchor.engine === 'PDFIUM' || Number(textAnchor.version) >= 2) {
    const charStart = Math.max(0, Number(textAnchor.charStart) || 0)
    const charEnd = Math.max(charStart, Number(textAnchor.charEnd) || 0)
    return {
      version: 2,
      engine: 'PDFIUM',
      page: Number(textAnchor.page) || 1,
      documentFingerprint: String(textAnchor.documentFingerprint || ''),
      textMapVersion: 1,
      charStart,
      charEnd,
      ranges: [],
      contentSegments: normalizePdfiumContentSegments(
        textAnchor.contentSegments, charStart, charEnd,
      ),
    }
  }
  return {
    version: Number(textAnchor.version) || 1,
    page: Number(textAnchor.page) || 1,
    documentFingerprint: String(textAnchor.documentFingerprint || ''),
    textMapVersion: Number(textAnchor.textMapVersion) || 1,
    ranges: (textAnchor.ranges || []).map(range => ({
      itemIndex: Number(range.itemIndex),
      spanIndex: Number(range.spanIndex),
      startOffset: Number(range.startOffset),
      endOffset: Number(range.endOffset),
    })),
  }
}

export function boundingBoxToViewportQuad(box) {
  if (!box) return null
  const x = clamp(box.x)
  const y = clamp(box.y)
  const right = clamp(x + Number(box.width || 0))
  const bottom = clamp(y + Number(box.height || 0))
  return {
    x1: x, y1: bottom,
    x2: right, y2: bottom,
    x3: right, y3: y,
    x4: x, y4: y
  }
}
