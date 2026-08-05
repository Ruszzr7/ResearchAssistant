export const PDF_WORKBENCH_WIDTH_KEY = 'research-assistant.pdf-workbench-width-ratio-v2'
export const DEFAULT_WORKBENCH_RATIO = 0.4
export const MIN_WORKBENCH_RATIO = 0.2
export const MAX_WORKBENCH_RATIO = 0.65
export const MIN_WORKBENCH_WIDTH = 320
export const MIN_COMPACT_WORKBENCH_WIDTH = 240
export const MIN_PDF_WIDTH = 480
export const DEFAULT_PDF_VIEWPORT_RESERVE = 20
export const WORKBENCH_DIVIDER_WIDTH = 8
export const DEFAULT_COMMENT_PANEL_WIDTH = 280
export const MIN_COMMENT_PANEL_WIDTH = 180
export const MIN_ASSISTANT_WITH_COMMENTS = 240

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value))
}

export function normalizeWorkbenchRatio(value, fallback = DEFAULT_WORKBENCH_RATIO) {
  if (value == null || value === '') return fallback
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return fallback
  return clamp(numeric, MIN_WORKBENCH_RATIO, MAX_WORKBENCH_RATIO)
}

export function completePdfPaneWidth(pageWidthAt100, viewportReserve = DEFAULT_PDF_VIEWPORT_RESERVE) {
  const pageWidth = Math.max(0, Number(pageWidthAt100) || 0)
  if (pageWidth === 0) return MIN_PDF_WIDTH
  return Math.ceil(Math.max(MIN_PDF_WIDTH, pageWidth + Math.max(0, Number(viewportReserve) || 0)))
}

export function workbenchWidthForContainer(
  containerWidth,
  ratio = DEFAULT_WORKBENCH_RATIO,
  minimumPdfWidth = MIN_PDF_WIDTH,
) {
  const width = Math.max(0, Number(containerWidth) || 0)
  const usableWidth = Math.max(0, width - WORKBENCH_DIVIDER_WIDTH)
  if (usableWidth === 0) return 0

  // The approved 60/40 workbench gives the assistant the space released by the
  // compact app rail. A wide PDF may scroll horizontally instead of forcing the
  // assistant below its requested ratio.
  void minimumPdfWidth
  const minimum = Math.min(MIN_WORKBENCH_WIDTH, usableWidth * 0.45)
  const maximum = Math.max(minimum, usableWidth - Math.min(MIN_PDF_WIDTH, usableWidth * 0.55))
  return Math.round(clamp(usableWidth * normalizeWorkbenchRatio(ratio), minimum, maximum))
}

export function ratioFromDividerPosition(clientX, containerRect) {
  const width = Number(containerRect?.width) || 0
  const right = Number(containerRect?.right)
  if (width <= 0 || !Number.isFinite(right)) return DEFAULT_WORKBENCH_RATIO
  return normalizeWorkbenchRatio((right - Number(clientX || 0)) / width)
}

export function splitWorkbenchAllocation(allocationWidth, commentsVisible) {
  const width = Math.max(0, Number(allocationWidth) || 0)
  if (!commentsVisible) return { commentWidth: 0, assistantWidth: Math.round(width) }
  if (width >= MIN_COMMENT_PANEL_WIDTH + MIN_ASSISTANT_WITH_COMMENTS) {
    const commentWidth = Math.min(DEFAULT_COMMENT_PANEL_WIDTH, width - MIN_ASSISTANT_WITH_COMMENTS)
    return { commentWidth: Math.round(commentWidth), assistantWidth: Math.round(width - commentWidth) }
  }
  const commentWidth = Math.round(width * 0.45)
  return { commentWidth, assistantWidth: Math.round(width - commentWidth) }
}

function resolveStorage(storage) {
  if (storage) return storage
  try { return globalThis.localStorage } catch { return null }
}

export function readWorkbenchRatio(storage) {
  const target = resolveStorage(storage)
  if (!target) return DEFAULT_WORKBENCH_RATIO
  try {
    return normalizeWorkbenchRatio(target.getItem(PDF_WORKBENCH_WIDTH_KEY))
  } catch {
    return DEFAULT_WORKBENCH_RATIO
  }
}

export function writeWorkbenchRatio(ratio, storage) {
  const normalized = normalizeWorkbenchRatio(ratio)
  const target = resolveStorage(storage)
  if (!target) return normalized
  try {
    target.setItem(PDF_WORKBENCH_WIDTH_KEY, String(normalized))
  } catch { /* storage can be disabled by the browser */ }
  return normalized
}
