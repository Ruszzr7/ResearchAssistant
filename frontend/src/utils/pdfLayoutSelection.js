import { distanceToRect } from '@/utils/pdfTextSelection.js'

// Dragging may leave a glyph by a fraction of a pixel because PDF.js applies
// transforms to spans. Keep this deliberately tighter than the old native
// Selection start tolerance so whitespace never resolves to a distant word.
export const PDF_LAYOUT_DRAG_HIT_SLOP = 2

/**
 * Finds the rendered horizontal text run nearest to a page-local pointer.
 * Vertical / rotated marginal metadata is intentionally not a text-selection
 * target in this first interaction slice.
 */
export function findLayoutRunAtPoint(index, x, y, hitSlop = PDF_LAYOUT_DRAG_HIT_SLOP) {
  if (!index?.runs?.length) return null

  let closest = null
  let closestDistance = Number.POSITIVE_INFINITY
  let closestVerticalRatio = Number.POSITIVE_INFINITY
  let closestHorizontalRatio = Number.POSITIVE_INFINITY
  for (const run of index.runs) {
    if (run.orientation !== 'horizontal') continue
    const distance = distanceToRect(x, y, {
      left: run.x,
      right: run.right,
      top: run.y,
      bottom: run.bottom
    })
    const verticalRatio = Math.abs(y - run.centerY) / Math.max(1, run.height)
    const horizontalRatio = Math.abs(x - run.centerX) / Math.max(1, run.width)
    if (distance < closestDistance
        || distance === closestDistance && verticalRatio < closestVerticalRatio
        || distance === closestDistance && verticalRatio === closestVerticalRatio
          && horizontalRatio < closestHorizontalRatio) {
      closest = run
      closestDistance = distance
      closestVerticalRatio = verticalRatio
      closestHorizontalRatio = horizontalRatio
    }
  }
  return closestDistance <= hitSlop ? closest : null
}

/**
 * Converts two text-run endpoints into one deterministic same-column range.
 * It follows the visual line order from the layout index instead of browser
 * DOM order, which is the key guard against a double-column native Range
 * absorbing nearby text from the other column.
 *
 * This deliberately returns null for a cross-column, full-width-to-column or
 * collapsed drag. Later phases will add explicit cross-column/paragraph links
 * instead of silently guessing that those runs are continuous prose.
 */
export function createSameColumnSelection(index, anchor, focus) {
  const runById = new Map((index?.runs || []).map(run => [run.id, run]))
  const anchorLocation = locateRun(index, runById, anchor)
  const focusLocation = locateRun(index, runById, focus)
  if (!anchorLocation || !focusLocation || anchorLocation.columnId !== focusLocation.columnId) return null

  const orderedRuns = orderedColumnRuns(index, runById, anchorLocation.columnId)
  const anchorIndex = orderedRuns.findIndex(run => run.id === anchorLocation.run.id)
  const focusIndex = orderedRuns.findIndex(run => run.id === focusLocation.run.id)
  if (anchorIndex < 0 || focusIndex < 0) return null

  const normalizedAnchor = endpointForRun(anchorLocation.run, anchor?.offset)
  const normalizedFocus = endpointForRun(focusLocation.run, focus?.offset)
  const anchorAfterFocus = anchorIndex > focusIndex
    || (anchorIndex === focusIndex && normalizedAnchor.offset > normalizedFocus.offset)
  const first = anchorAfterFocus ? normalizedFocus : normalizedAnchor
  const last = anchorAfterFocus ? normalizedAnchor : normalizedFocus
  const firstIndex = anchorAfterFocus ? focusIndex : anchorIndex
  const lastIndex = anchorAfterFocus ? anchorIndex : focusIndex
  if (firstIndex === lastIndex && first.offset === last.offset) return null

  const segments = orderedRuns.slice(firstIndex, lastIndex + 1).flatMap((run, relativeIndex, rangeRuns) => {
    const startOffset = relativeIndex === 0 ? first.offset : 0
    const endOffset = relativeIndex === rangeRuns.length - 1 ? last.offset : run.text.length
    if (endOffset <= startOffset) return []
    return [{
      runId: run.id,
      startOffset,
      endOffset,
      text: run.text.slice(startOffset, endOffset)
    }]
  })
  if (!segments.length) return null

  return {
    columnId: anchorLocation.columnId,
    anchor: normalizedAnchor,
    focus: normalizedFocus,
    start: first,
    end: last,
    segments,
    text: joinSelectedText(segments.map(segment => segment.text))
  }
}

function locateRun(index, runById, endpoint) {
  const run = runById.get(endpoint?.runId)
  if (!run || run.orientation !== 'horizontal') return null
  const line = (index?.lines || []).find(candidate => candidate.runIds?.includes(run.id))
  // A run that spans a detected column gutter is usually a display equation,
  // caption or page furniture. It must not be used as a bridge that pulls a
  // neighbouring prose column into the current selection.
  if (!line?.columnId || line.columnId === 'ambiguous') return null
  return { run, columnId: line.columnId }
}

function orderedColumnRuns(index, runById, columnId) {
  return (index?.lines || [])
    .filter(line => line.columnId === columnId)
    .flatMap(line => (line.runIds || [])
      .map(id => runById.get(id))
      .filter(Boolean)
      .map(run => ({
        run,
        selectionRowIndex: Number.isInteger(line.selectionRowIndex) ? line.selectionRowIndex : null,
        selectionOrderY: Number.isFinite(Number(line.selectionOrderY)) ? Number(line.selectionOrderY) : line.centerY
      })))
    .sort((a, b) => {
      if (a.selectionRowIndex != null && b.selectionRowIndex != null
          && a.selectionRowIndex !== b.selectionRowIndex) {
        return a.selectionRowIndex - b.selectionRowIndex
      }
      return a.selectionOrderY - b.selectionOrderY
        || a.run.x - b.run.x
        || a.run.centerY - b.run.centerY
        || a.run.sourceIndex - b.run.sourceIndex
    })
    .map(item => item.run)
}

function endpointForRun(run, offset) {
  const numericOffset = Number(offset)
  const safeOffset = Number.isFinite(numericOffset) ? Math.round(numericOffset) : 0
  return {
    runId: run.id,
    offset: Math.max(0, Math.min(run.text.length, safeOffset))
  }
}

function joinSelectedText(parts) {
  return parts
    .filter(Boolean)
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim()
}
