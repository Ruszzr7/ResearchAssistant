export const PDF_WORKBENCH_WIDTH_KEY = 'research-assistant.pdf-workbench-width-ratio'
export const DEFAULT_WORKBENCH_RATIO = 0.4
export const MIN_WORKBENCH_RATIO = 0.2
export const MAX_WORKBENCH_RATIO = 0.65
export const MIN_WORKBENCH_WIDTH = 320
export const MIN_COMPACT_WORKBENCH_WIDTH = 240
export const MIN_PDF_WIDTH = 480
export const DEFAULT_PDF_VIEWPORT_RESERVE = 20
export const WORKBENCH_DIVIDER_WIDTH = 8

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

  const requestedPdfWidth = Math.max(MIN_PDF_WIDTH, Number(minimumPdfWidth) || MIN_PDF_WIDTH)
  const completePageMaximum = usableWidth - requestedPdfWidth
  let minimum
  let maximum
  if (completePageMaximum >= MIN_COMPACT_WORKBENCH_WIDTH) {
    // The divider may move left only while a complete 100% PDF page still fits.
    maximum = completePageMaximum
    minimum = Math.min(MIN_WORKBENCH_WIDTH, maximum)
  } else {
    // Very narrow windows cannot show a full page and a usable assistant at the
    // same time. Keep both panes operable and let the PDF retain horizontal scroll.
    minimum = Math.min(MIN_WORKBENCH_WIDTH, usableWidth * 0.45)
    maximum = Math.max(minimum, usableWidth - Math.min(MIN_PDF_WIDTH, usableWidth * 0.55))
  }
  return Math.round(clamp(usableWidth * normalizeWorkbenchRatio(ratio), minimum, maximum))
}

export function ratioFromDividerPosition(clientX, containerRect) {
  const width = Number(containerRect?.width) || 0
  const right = Number(containerRect?.right)
  if (width <= 0 || !Number.isFinite(right)) return DEFAULT_WORKBENCH_RATIO
  return normalizeWorkbenchRatio((right - Number(clientX || 0)) / width)
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
