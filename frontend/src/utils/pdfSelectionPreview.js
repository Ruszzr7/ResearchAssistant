const clamp = value => Math.max(0, Math.min(1, Number(value) || 0))

function quadBounds(quad) {
  if (!quad) return null
  const xs = [quad.x1, quad.x2, quad.x3, quad.x4].map(clamp)
  const ys = [quad.y1, quad.y2, quad.y3, quad.y4].map(clamp)
  const x = Math.min(...xs)
  const y = Math.min(...ys)
  const right = Math.max(...xs)
  const bottom = Math.max(...ys)
  return right > x && bottom > y ? { x, y, right, bottom } : null
}

export function selectionPreviewBounds(quads, padding = 0.008) {
  const boxes = (quads || []).map(quadBounds).filter(Boolean)
  if (!boxes.length) return null
  const x = Math.max(0, Math.min(...boxes.map(box => box.x)) - padding)
  const y = Math.max(0, Math.min(...boxes.map(box => box.y)) - padding)
  const right = Math.min(1, Math.max(...boxes.map(box => box.right)) + padding)
  const bottom = Math.min(1, Math.max(...boxes.map(box => box.bottom)) + padding)
  return right > x && bottom > y
    ? { x, y, width: right - x, height: bottom - y }
    : null
}

export function selectionNeedsVisualFallback(contentSegments, textNormalization) {
  return Boolean(
    textNormalization?.hasExtractionIssues
    || (contentSegments || []).some(segment => segment?.type && segment.type !== 'TEXT'),
  )
}

/**
 * Crop one bounded preview from the already-rendered PDF canvas. The returned
 * data URL is viewer-local and is not part of the persisted selection anchor.
 */
export function createPdfSelectionPreview(sourceCanvas, quads, {
  maxWidth = 1200,
  maxHeight = 1200,
  canvasFactory = () => document.createElement('canvas'),
} = {}) {
  try {
    if (!sourceCanvas?.width || !sourceCanvas?.height) return null
    const bounds = selectionPreviewBounds(quads)
    if (!bounds) return null

    const sourceX = Math.max(0, Math.floor(bounds.x * sourceCanvas.width))
    const sourceY = Math.max(0, Math.floor(bounds.y * sourceCanvas.height))
    const sourceWidth = Math.max(1, Math.ceil(bounds.width * sourceCanvas.width))
    const sourceHeight = Math.max(1, Math.ceil(bounds.height * sourceCanvas.height))
    const scale = Math.min(1, maxWidth / sourceWidth, maxHeight / sourceHeight)
    const canvas = canvasFactory()
    canvas.width = Math.max(1, Math.round(sourceWidth * scale))
    canvas.height = Math.max(1, Math.round(sourceHeight * scale))
    const context = canvas.getContext?.('2d')
    if (!context) return null
    context.drawImage(
      sourceCanvas,
      sourceX, sourceY, sourceWidth, sourceHeight,
      0, 0, canvas.width, canvas.height,
    )
    return {
      dataUrl: canvas.toDataURL('image/png'),
      width: canvas.width,
      height: canvas.height,
      bounds,
    }
  } catch {
    return null
  }
}
