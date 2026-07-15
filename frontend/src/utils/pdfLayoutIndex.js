/**
 * Lightweight, viewport-space layout index for a rendered PDF.js text layer.
 *
 * The viewer deliberately builds this from the DOM rectangles that PDF.js has
 * already rendered instead of trying to re-interpret PDF transformation
 * matrices a second time. It is a local interaction aid, not the persisted
 * backend PaperLayoutArtifact described in the workbench specification.
 *
 * A PDF.js span is a text run rather than a guaranteed single glyph. Keeping
 * those run rectangles is enough for the next selection slice (precise hit
 * testing and line-aware ranges); character-level boxes and formula regions
 * are intentionally deferred until that interaction is introduced.
 */

const MIN_PAGE_SIZE = 1

export function buildPdfPageLayoutIndex({ pageNum, pageWidth, pageHeight, textItems = [] } = {}) {
  const width = positiveNumber(pageWidth, MIN_PAGE_SIZE)
  const height = positiveNumber(pageHeight, MIN_PAGE_SIZE)
  const runs = normalizeTextRuns(textItems, { pageWidth: width, pageHeight: height })
  const horizontalRuns = runs.filter(run => run.orientation === 'horizontal')
  const verticalRuns = runs.filter(run => run.orientation === 'vertical')
  const lines = clusterTextRunsIntoLines(horizontalRuns, { pageWidth: width })
  const columnResult = detectLayoutColumns(lines, {
    pageWidth: width,
    pageHeight: height,
    runs
  })
  const paragraphs = groupParagraphCandidates(columnResult.lines, { columns: columnResult.columns })

  return {
    version: 1,
    coordinateSpace: 'viewport',
    pageNum: Number(pageNum) || 1,
    pageWidth: width,
    pageHeight: height,
    // `runs` preserves the rendered text geometry for future custom hit tests.
    runs,
    verticalRuns,
    lines: columnResult.lines,
    columns: columnResult.columns,
    paragraphs,
    readingOrder: buildReadingOrder(columnResult.lines, columnResult.columns),
    stats: {
      runCount: runs.length,
      horizontalRunCount: horizontalRuns.length,
      verticalRunCount: verticalRuns.length,
      lineCount: columnResult.lines.length,
      columnCount: columnResult.columns.length,
      paragraphCandidateCount: paragraphs.length
    }
  }
}

/**
 * Normalizes browser-measured text rectangles into page-local coordinates.
 * Explicit orientation is accepted for deterministic tests or future PDF.js
 * transform inspection; otherwise only clearly rotated/tall text is marked
 * vertical. Horizontal side-margin metadata stays in the index for now.
 */
export function normalizeTextRuns(items, { pageWidth = MIN_PAGE_SIZE, pageHeight = MIN_PAGE_SIZE } = {}) {
  if (!Array.isArray(items)) return []

  return items.flatMap((item, sourceIndex) => {
    const sourceText = String(item?.text || '').replace(/\u00a0/g, ' ')
    const text = sourceText.trim()
    const x = finiteNumber(item?.x)
    const y = finiteNumber(item?.y)
    const width = finiteNumber(item?.width)
    const height = finiteNumber(item?.height)
    if (!text || x == null || y == null || width == null || height == null || width <= 0 || height <= 0) {
      return []
    }

    const explicitOrientation = item?.orientation === 'vertical' || item?.orientation === 'horizontal'
      ? item.orientation
      : null
    const orientation = explicitOrientation || inferOrientation({ text, width, height })
    const right = x + width
    const bottom = y + height
    const textStartOffset = sourceText.length - sourceText.trimStart().length

    return [{
      id: String(item?.id || `run-${sourceIndex}`),
      sourceIndex,
      text,
      sourceText,
      textStartOffset,
      x,
      y,
      width,
      height,
      right,
      bottom,
      centerX: x + width / 2,
      centerY: y + height / 2,
      orientation,
      // Keep the original page-local bounds available without assuming that
      // off-page text is invalid (some PDFs intentionally draw at the edge).
      withinPage: x >= 0 && y >= 0 && right <= pageWidth && bottom <= pageHeight
    }]
  })
}

/**
 * Groups horizontally rendered text runs into visual lines. The y grouping
 * happens first, then large x gaps split same-baseline left/right columns into
 * separate lines instead of concatenating them as native Selection does.
 */
export function clusterTextRunsIntoLines(runs, { pageWidth = MIN_PAGE_SIZE } = {}) {
  if (!Array.isArray(runs) || !runs.length) return []

  const sorted = [...runs].sort((a, b) => a.centerY - b.centerY || a.x - b.x)
  const medianHeight = median(sorted.map(run => run.height)) || 1
  const yTolerance = clamp(medianHeight * 0.65, 2, 10)
  const rowBands = []

  for (const run of sorted) {
    let band = null
    for (let index = rowBands.length - 1; index >= 0; index -= 1) {
      const candidate = rowBands[index]
      if (Math.abs(run.centerY - candidate.centerY) <= yTolerance) {
        band = candidate
        break
      }
      if (run.centerY - candidate.centerY > yTolerance) break
    }
    if (!band) {
      band = { runs: [], centerY: run.centerY }
      rowBands.push(band)
    }
    band.runs.push(run)
    band.centerY = average(band.runs.map(item => item.centerY))
  }

  const gapThreshold = Math.max(medianHeight * 1.65, positiveNumber(pageWidth, MIN_PAGE_SIZE) * 0.018)
  // A rendered double-column PDF can have a gutter narrower than the old
  // generic "large gap" threshold. That is common when PDF.js exposes every
  // word as a separate span: the 18px gap between columns is only a little
  // wider than a regular word space. Detect a stable vertical gutter first so
  // the later line construction never merges left and right prose into one
  // artificial full-width line.
  const stableGutter = detectStableColumnGutter(rowBands, {
    pageWidth,
    medianHeight
  })
  const segmentedLines = []
  for (const band of rowBands) {
    const orderedRuns = [...band.runs].sort((a, b) => a.x - b.x || a.centerY - b.centerY)
    let segment = []
    for (const run of orderedRuns) {
      const previous = segment[segment.length - 1]
      const gap = previous ? run.x - previous.right : 0
      const crossesStableGutter = previous && stableGutter
        ? previous.right <= stableGutter.x
          && run.x >= stableGutter.x
          && gap >= stableGutter.minGap
        : false
      if (previous && (crossesStableGutter || gap > gapThreshold)) {
        segmentedLines.push(createLine(segment))
        segment = []
      }
      segment.push(run)
    }
    if (segment.length) segmentedLines.push(createLine(segment))
  }

  return segmentedLines
    .sort((a, b) => a.centerY - b.centerY || a.x - b.x)
    .map((line, index) => ({ ...line, id: `line-${index}` }))
}

/**
 * Finds a repeated whitespace corridor around the centre of a page. Unlike a
 * one-off title/author word space, a real double-column gutter appears on
 * several visual baselines at nearly the same x coordinate. We use that
 * repeated geometry as a higher-confidence split signal than gap width alone.
 */
function detectStableColumnGutter(rowBands, { pageWidth, medianHeight }) {
  const width = positiveNumber(pageWidth, MIN_PAGE_SIZE)
  const centreX = width / 2
  const minGutterGap = Math.max(medianHeight * 0.85, width * 0.008)
  const centreWindow = width * 0.15
  const candidates = []

  for (const band of rowBands) {
    const orderedRuns = [...band.runs].sort((a, b) => a.x - b.x || a.centerY - b.centerY)
    for (let index = 1; index < orderedRuns.length; index += 1) {
      const previous = orderedRuns[index - 1]
      const run = orderedRuns[index]
      const gap = run.x - previous.right
      const x = (previous.right + run.x) / 2
      if (gap < minGutterGap
        || previous.right > centreX
        || run.x < centreX
        || Math.abs(x - centreX) > centreWindow) {
        continue
      }
      candidates.push({ x, gap })
    }
  }

  if (candidates.length < 4) return null

  const tolerance = Math.max(medianHeight * 1.25, width * 0.012)
  const groups = []
  for (const candidate of [...candidates].sort((a, b) => a.x - b.x || a.gap - b.gap)) {
    const latest = groups[groups.length - 1]
    if (!latest || candidate.x - latest.x > tolerance) {
      groups.push({ x: candidate.x, candidates: [candidate] })
      continue
    }
    latest.candidates.push(candidate)
    latest.x = median(latest.candidates.map(item => item.x))
  }

  const best = groups
    .filter(group => group.candidates.length >= 4)
    .map(group => ({
      ...group,
      medianGap: median(group.candidates.map(item => item.gap)),
      score: group.candidates.length * 100 - Math.abs(group.x - centreX)
    }))
    .sort((a, b) => b.score - a.score || b.medianGap - a.medianGap)[0]

  if (!best) return null
  return {
    x: best.x,
    // Keep ordinary word spacing out, while tolerating a compact PDF gutter.
    minGap: Math.max(minGutterGap, best.medianGap * 0.7)
  }
}

/**
 * Detects only the single/double-column distinction needed by the initial
 * workbench path. It deliberately needs several aligned lines per column, so
 * a centered title, author row or a one-off formula cannot create a false
 * second column.
 */
export function detectLayoutColumns(lines, {
  pageWidth = MIN_PAGE_SIZE,
  pageHeight = MIN_PAGE_SIZE,
  runs = []
} = {}) {
  if (!Array.isArray(lines) || !lines.length) return { lines: [], columns: [] }

  const width = positiveNumber(pageWidth, MIN_PAGE_SIZE)
  const height = positiveNumber(pageHeight, MIN_PAGE_SIZE)
  const medianHeight = median(lines.map(line => line.height)) || 1
  const fullWidthThreshold = width * 0.72
  const anchorThreshold = Math.max(width * 0.12, medianHeight * 5)
  const candidateLines = lines.filter(line => (
    line.width < fullWidthThreshold
    && line.text.length >= 2
    && !isCenteredMastheadLine(line, { pageWidth: width, pageHeight: height, medianHeight })
  ))
  const anchorGroups = groupLineAnchors(candidateLines, anchorThreshold)
  const pair = bestColumnPair(anchorGroups, width)

  if (!pair) {
    const singleColumnLines = assignSelectionRowOrder(lines.map(line => ({
      ...line,
      // A paper title can span several centred lines while the rest of the
      // page is single-column. Keep those lines in one explicit lane so a
      // drag across the title never turns into unrelated body text.
      columnId: isCenteredMastheadLine(line, {
        pageWidth: width,
        pageHeight: height,
        medianHeight
      }) ? 'full' : 'column-0'
    })), { fallbackHeight: medianHeight })
    return {
      lines: singleColumnLines,
      columns: [createColumn('column-0', 0, singleColumnLines)]
    }
  }

  const selectedGroups = [...pair].sort((a, b) => a.anchor - b.anchor)
  const columnBoundary = estimateColumnBoundary(selectedGroups, width)
  const splitTolerance = Math.max(2, medianHeight * 0.22)
  const localLines = splitLinesAtColumnBoundary(lines, runs, columnBoundary, splitTolerance)
    .sort((a, b) => a.centerY - b.centerY || a.x - b.x)
    .map((line, index) => ({ ...line, id: `line-${index}` }))
  const assignedLines = assignSelectionRowOrder(localLines.map(line => ({
    ...line,
    columnId: classifyLineLane(line, {
      pageWidth: width,
      pageHeight: height,
      medianHeight,
      columnBoundary,
      splitTolerance
    })
  })), { fallbackHeight: medianHeight })
  const columns = selectedGroups.map((_, index) => {
    const id = `column-${index}`
    return createColumn(id, index, assignedLines.filter(line => line.columnId === id))
  })

  return { lines: assignedLines, columns }
}

/**
 * A page-wide column model is insufficient for IEEE layouts: biography text
 * can wrap around a portrait, and appendix equations can occupy only one side
 * of an otherwise two-column page.  When PDF.js happened to group both sides
 * onto one baseline, split the run list at the detected gutter *before*
 * selection assigns a lane. A formula glyph can visually spill a few pixels
 * into that gutter because of italic slant, radicals or super/subscripts. If
 * the rest of its baseline has prose on only one side, keep that formula in
 * the same lane; only a fragment that actually sits between both columns is
 * marked ambiguous rather than contaminating either prose column.
 */
function splitLinesAtColumnBoundary(lines, runs, columnBoundary, tolerance) {
  const runById = new Map((runs || []).map(run => [run.id, run]))
  return lines.flatMap(line => {
    const lineRuns = (line.runIds || []).map(id => runById.get(id)).filter(Boolean)
    if (lineRuns.length < 2) return [line]

    const left = []
    const right = []
    const crossing = []
    for (const run of lineRuns) {
      if (run.right <= columnBoundary + tolerance) left.push(run)
      else if (run.x >= columnBoundary - tolerance) right.push(run)
      else crossing.push(run)
    }

    // A normal row has all runs on one side. Do not recreate it: preserving
    // its original object avoids needless id churn in ordinary one-column
    // paragraphs.
    const occupiedSides = [left, crossing, right].filter(part => part.length).length
    if (occupiedSides < 2) return [line]

    // Mathematical glyph bounds often overhang the central gutter even when
    // the surrounding sentence belongs entirely to one column. Treat that as
    // an inline formula, not as a cross-column bridge. A true bridge has
    // trusted content on *both* sides and remains split below.
    if (crossing.length && left.length && !right.length) {
      return [createSplitLine([...left, ...crossing], 'column-0')]
    }
    if (crossing.length && right.length && !left.length) {
      return [createSplitLine([...crossing, ...right], 'column-1')]
    }

    const split = []
    if (left.length) split.push(createSplitLine(left, 'column-0'))
    if (crossing.length) split.push(createSplitLine(crossing, 'ambiguous'))
    if (right.length) split.push(createSplitLine(right, 'column-1'))
    return split.length ? split : [line]
  })
}

function createSplitLine(runs, laneHint) {
  return {
    ...createLine([...runs].sort((a, b) => a.x - b.x || a.centerY - b.centerY || a.sourceIndex - b.sourceIndex)),
    laneHint
  }
}

function classifyLineLane(line, {
  pageWidth,
  pageHeight,
  medianHeight,
  columnBoundary,
  splitTolerance
}) {
  if (isCenteredMastheadLine(line, { pageWidth, pageHeight, medianHeight })) return 'full'
  if (line.laneHint) return line.laneHint

  // Figure captions, page furniture and display equations may legitimately
  // cross the gutter. They are not safe candidates for a prose drag. Keeping
  // a dedicated lane lets the selector reject them instead of silently
  // borrowing runs from both columns.
  if (line.x < columnBoundary - splitTolerance && line.right > columnBoundary + splitTolerance) {
    return 'ambiguous'
  }

  // Start x alone fails beside an embedded portrait: the text begins farther
  // right than the column anchor above the image, but its centre still belongs
  // to the left column. Classifying by centre keeps the paragraph continuous
  // above and below the image.
  return line.centerX < columnBoundary ? 'column-0' : 'column-1'
}

/**
 * PDF.js can expose the accent, base glyph and subscript of one inline formula
 * as separate visual fragments. Their bounding-box centres differ, even though
 * they sit on the same prose baseline. Give nearby fragments one row-order key
 * so selection can sort all of their runs by x before advancing to the next
 * line. This is deliberately a selection-only order; semantic reading order
 * continues to use the conservative line model below.
 */
function assignSelectionRowOrder(lines, { fallbackHeight = 1 } = {}) {
  const laneGroups = new Map()
  for (const line of lines) {
    const laneId = line.columnId || 'unassigned'
    if (!laneGroups.has(laneId)) laneGroups.set(laneId, [])
    laneGroups.get(laneId).push(line)
  }

  const selectionOrderById = new Map()
  for (const laneLines of laneGroups.values()) {
    const medianHeight = median(laneLines.map(line => line.height)) || fallbackHeight || 1
    // 8px covers the vertical offset of normal superscripts/subscripts at the
    // viewer's common scales, while remaining well below a normal IEEE line
    // spacing (about 18px at 100%).
    const baselineTolerance = clamp(medianHeight * 0.55, 3, 8)
    const ordered = [...laneLines].sort((a, b) => selectionBaseline(a) - selectionBaseline(b) || a.x - b.x)
    const rows = []

    for (const line of ordered) {
      const baseline = selectionBaseline(line)
      let row = rows[rows.length - 1]
      if (!row || Math.abs(baseline - row.baseline) > baselineTolerance) {
        row = { baselines: [], lines: [], baseline }
        rows.push(row)
      }
      row.baselines.push(baseline)
      row.lines.push(line)
      row.baseline = median(row.baselines)
    }

    for (const row of rows) {
      for (const line of row.lines) selectionOrderById.set(line.id, row.baseline)
    }
  }

  return lines.map(line => ({
    ...line,
    selectionOrderY: selectionOrderById.get(line.id) ?? selectionBaseline(line)
  }))
}

function selectionBaseline(line) {
  const baseline = finiteNumber(line?.baselineY)
  if (baseline != null) return baseline
  const bottom = finiteNumber(line?.bottom)
  if (bottom != null) return bottom
  return finiteNumber(line?.centerY) ?? 0
}

function isCenteredDisplayHeader(line, { pageWidth, pageHeight, medianHeight }) {
  const width = positiveNumber(pageWidth, MIN_PAGE_SIZE)
  const height = positiveNumber(pageHeight, MIN_PAGE_SIZE)
  const lineHeight = positiveNumber(line?.height, 0)
  const lineWidth = positiveNumber(line?.width, 0)
  const centreX = finiteNumber(line?.x) == null ? null : line.x + lineWidth / 2
  if (centreX == null) return false

  return line.y <= height * 0.42
    && lineHeight >= Math.max(medianHeight * 1.45, 16)
    && lineWidth >= Math.max(width * 0.12, medianHeight * 5)
    && Math.abs(centreX - width / 2) <= width * 0.08
}

function isCenteredMastheadLine(line, context) {
  if (isCenteredDisplayHeader(line, context)) return true

  const width = positiveNumber(context?.pageWidth, MIN_PAGE_SIZE)
  const height = positiveNumber(context?.pageHeight, MIN_PAGE_SIZE)
  const medianHeight = positiveNumber(context?.medianHeight, 1)
  const lineHeight = positiveNumber(line?.height, 0)
  const lineWidth = positiveNumber(line?.width, 0)
  const centreX = finiteNumber(line?.x) == null ? null : line.x + lineWidth / 2
  if (centreX == null) return false

  // IEEE author rows are commonly smaller than the title but still centred
  // and page-wide. Treat them as masthead content so the reading order keeps
  // them between the title and the two-column body instead of assigning the
  // row to whichever column happens to be geometrically closest.
  return line.y <= height * 0.28
    && lineHeight >= Math.max(medianHeight * 0.8, 10)
    && lineWidth >= width * 0.35
    && Math.abs(centreX - width / 2) <= width * 0.08
}

function estimateColumnBoundary(groups, pageWidth) {
  const [leftGroup, rightGroup] = groups || []
  if (!leftGroup || !rightGroup) return positiveNumber(pageWidth, MIN_PAGE_SIZE) / 2

  // The x anchors are column *starts*, so their midpoint sits inside the left
  // column on a conventional two-column IEEE page. Estimate the actual gutter
  // from the right edge of stable left lines and the left edge of stable right
  // lines instead. It also handles local text that starts after a portrait.
  const leftEdge = percentile(leftGroup.lines.map(line => line.right), 0.75)
  const rightEdge = percentile(rightGroup.lines.map(line => line.x), 0.25)
  if (Number.isFinite(leftEdge) && Number.isFinite(rightEdge) && rightEdge > leftEdge) {
    return (leftEdge + rightEdge) / 2
  }
  return (leftGroup.anchor + rightGroup.anchor) / 2
}

/**
 * Produces conservative visual paragraph candidates within a column. These
 * are not semantic blocks yet: later parser artifacts will provide roles and
 * section-aware boundaries. The candidates simply prevent future pointer
 * selection from blindly jumping across a large vertical gap.
 */
export function groupParagraphCandidates(lines, { columns = [] } = {}) {
  if (!Array.isArray(lines) || !lines.length) return []

  const columnIds = columns.length ? columns.map(column => column.id) : [...new Set(lines.map(line => line.columnId))]
  const paragraphs = []
  for (const columnId of columnIds) {
    const ordered = lines
      .filter(line => line.columnId === columnId)
      .sort((a, b) => a.y - b.y || a.x - b.x)
    if (!ordered.length) continue

    const medianHeight = median(ordered.map(line => line.height)) || 1
    const maxJoinGap = Math.max(medianHeight * 1.9, 6)
    let paragraphLines = []
    for (const line of ordered) {
      const previous = paragraphLines[paragraphLines.length - 1]
      const gap = previous ? line.y - previous.bottom : 0
      if (previous && gap > maxJoinGap) {
        paragraphs.push(createParagraph(columnId, paragraphLines, paragraphs.length))
        paragraphLines = []
      }
      paragraphLines.push(line)
    }
    if (paragraphLines.length) paragraphs.push(createParagraph(columnId, paragraphLines, paragraphs.length))
  }
  return paragraphs
}

function buildReadingOrder(lines, columns) {
  if (!lines.length) return []
  if (columns.length <= 1) return lines
    .sort((a, b) => a.y - b.y || a.x - b.x)
    .map(line => line.id)

  const fullWidthLines = lines
    .filter(line => line.columnId === 'full')
    .sort((a, b) => a.centerY - b.centerY || a.x - b.x)
  const perColumn = columns.map(column => lines
    .filter(line => line.columnId === column.id)
    .sort((a, b) => a.centerY - b.centerY || a.x - b.x))
  const consumed = new Set()
  const result = []

  const appendColumnsBefore = (yBoundary) => {
    for (const columnLines of perColumn) {
      for (const line of columnLines) {
        if (!consumed.has(line.id) && line.centerY < yBoundary) {
          result.push(line.id)
          consumed.add(line.id)
        }
      }
    }
  }

  for (const fullWidthLine of fullWidthLines) {
    appendColumnsBefore(fullWidthLine.centerY)
    result.push(fullWidthLine.id)
    consumed.add(fullWidthLine.id)
  }
  appendColumnsBefore(Number.POSITIVE_INFINITY)

  return result
}

function groupLineAnchors(lines, threshold) {
  const groups = []
  for (const line of [...lines].sort((a, b) => a.x - b.x || a.y - b.y)) {
    const latest = groups[groups.length - 1]
    if (!latest || line.x - latest.anchor > threshold) {
      groups.push({ anchor: line.x, lines: [line] })
      continue
    }
    latest.lines.push(line)
    latest.anchor = median(latest.lines.map(item => item.x))
  }
  return groups.filter(group => group.lines.length >= 3)
}

function bestColumnPair(groups, pageWidth) {
  let best = null
  for (let left = 0; left < groups.length; left += 1) {
    for (let right = left + 1; right < groups.length; right += 1) {
      const first = groups[left]
      const second = groups[right]
      const separation = Math.abs(second.anchor - first.anchor)
      if (separation < pageWidth * 0.2) continue
      const score = Math.min(first.lines.length, second.lines.length) * 100
        + first.lines.length + second.lines.length + separation / pageWidth
      if (!best || score > best.score) best = { score, groups: [first, second] }
    }
  }
  return best?.groups || null
}

function createLine(runs) {
  const x = Math.min(...runs.map(run => run.x))
  const y = Math.min(...runs.map(run => run.y))
  const right = Math.max(...runs.map(run => run.right))
  const bottom = Math.max(...runs.map(run => run.bottom))
  return {
    runIds: runs.map(run => run.id),
    text: runs.map(run => run.text).join(' ').replace(/\s+/g, ' ').trim(),
    x,
    y,
    width: right - x,
    height: bottom - y,
    right,
    bottom,
    centerX: (x + right) / 2,
    centerY: (y + bottom) / 2,
    // A median of run bottoms is stable when an inline formula contributes a
    // small accent or subscript outside the regular line box.
    baselineY: median(runs.map(run => run.bottom))
  }
}

function createColumn(id, order, lines) {
  const ordered = [...lines].sort((a, b) => a.y - b.y || a.x - b.x)
  if (!ordered.length) return { id, order, x: 0, width: 0, lineIds: [] }
  const x = Math.min(...ordered.map(line => line.x))
  const right = Math.max(...ordered.map(line => line.right))
  return {
    id,
    order,
    x,
    width: right - x,
    lineIds: ordered.map(line => line.id)
  }
}

function createParagraph(columnId, lines, index) {
  const x = Math.min(...lines.map(line => line.x))
  const y = Math.min(...lines.map(line => line.y))
  const right = Math.max(...lines.map(line => line.right))
  const bottom = Math.max(...lines.map(line => line.bottom))
  return {
    id: `paragraph-${index}`,
    columnId,
    lineIds: lines.map(line => line.id),
    text: lines.map(line => line.text).join(' ').replace(/\s+/g, ' ').trim(),
    x,
    y,
    width: right - x,
    height: bottom - y
  }
}

function inferOrientation({ text, width, height }) {
  // A short horizontal word can be narrower than its font height. Require a
  // very strong aspect ratio before treating unlabelled text as rotated.
  return text.length >= 3 && height > Math.max(width * 2.5, 18) ? 'vertical' : 'horizontal'
}

function finiteNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function positiveNumber(value, fallback) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : fallback
}

function median(values) {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

function percentile(values, fraction) {
  if (!values.length) return Number.NaN
  const sorted = [...values].sort((a, b) => a - b)
  const normalized = clamp(finiteNumber(fraction) ?? 0.5, 0, 1)
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * normalized) - 1))
  return sorted[index]
}

function average(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}
