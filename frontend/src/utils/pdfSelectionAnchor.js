const EPSILON = 1e-6

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
      contentSegments: (textAnchor.contentSegments || []).slice(0, 100).map(segment => ({
        type: String(segment.type || 'TEXT'),
        charStart: Math.max(charStart, Number(segment.charStart) || charStart),
        charEnd: Math.min(charEnd, Number(segment.charEnd) || charEnd),
        text: String(segment.text || '').slice(0, 1200),
        fonts: (segment.fonts || []).slice(0, 8).map(font => String(font).slice(0, 128)),
        rect: segment.rect ? { ...segment.rect } : null,
      })).filter(segment => segment.text.trim() && segment.charEnd >= segment.charStart),
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
