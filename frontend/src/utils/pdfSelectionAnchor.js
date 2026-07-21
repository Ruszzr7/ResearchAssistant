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
  return {
    page: group.pageNum,
    boxes,
    anchorText: String(selection.text || '').slice(0, 8000)
  }
}

/**
 * Persist a character-level client anchor beside normalized geometry. The
 * backend still resolves canonical evidence from boxes and document hash; this
 * reference lets the viewer reconstruct the exact PDF.js text range after a
 * TextLayer rerender without confusing source item indexes with DOM spans.
 */
export function buildSelectionTextAnchor(selection, layoutIndex, {
  documentFingerprint = '',
  textMapVersion = 1,
} = {}) {
  const ranges = (selection?.segments || []).flatMap(segment => {
    const run = layoutIndex?.runs?.find(candidate => candidate.id === segment.runId)
    if (!run || !Number.isInteger(run.itemIndex) || !Number.isInteger(run.spanIndex)) return []
    const leadingOffset = Number(run.textStartOffset) || 0
    return [{
      itemIndex: run.itemIndex,
      spanIndex: run.spanIndex,
      startOffset: leadingOffset + segment.startOffset,
      endOffset: leadingOffset + segment.endOffset,
    }]
  })
  if (!ranges.length) return null
  return {
    version: 1,
    page: Number(layoutIndex?.pageNum) || 1,
    documentFingerprint: String(documentFingerprint || ''),
    textMapVersion: Number(textMapVersion) || 1,
    ranges,
  }
}

export function restoreSelectionText(textAnchor, pageTextMap) {
  if (!textAnchor || !pageTextMap) return { status: 'INVALID', text: '' }
  if (Number(textAnchor.page) !== Number(pageTextMap.page)) return { status: 'PAGE_MISMATCH', text: '' }
  if (textAnchor.documentFingerprint && pageTextMap.documentFingerprint
    && textAnchor.documentFingerprint !== pageTextMap.documentFingerprint) {
    return { status: 'DOCUMENT_MISMATCH', text: '' }
  }

  const runByItem = new Map((pageTextMap.runs || []).map(run => [run.itemIndex, run]))
  const parts = []
  for (const range of textAnchor.ranges || []) {
    const run = runByItem.get(range.itemIndex)
    if (!run || run.spanIndex !== range.spanIndex) return { status: 'RANGE_MISMATCH', text: '' }
    const start = Math.max(0, Math.min(run.rawText.length, Number(range.startOffset) || 0))
    const end = Math.max(start, Math.min(run.rawText.length, Number(range.endOffset) || 0))
    if (end > start) parts.push(run.rawText.slice(start, end))
  }
  return {
    status: parts.length ? 'READY' : 'INVALID',
    text: parts.join(' ').replace(/\s+/g, ' ').trim(),
  }
}

export function cloneSelectionTextAnchor(textAnchor) {
  if (!textAnchor) return null
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
