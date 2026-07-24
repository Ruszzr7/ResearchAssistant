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

function previewMaskRects(quads, bounds, width, height, paddingPixels) {
  return (quads || []).map(quadBounds).filter(Boolean).map(box => {
    const x = (box.x - bounds.x) / bounds.width * width
    const y = (box.y - bounds.y) / bounds.height * height
    const right = (box.right - bounds.x) / bounds.width * width
    const bottom = (box.bottom - bounds.y) / bounds.height * height
    const left = Math.max(0, Math.floor(x - paddingPixels))
    const top = Math.max(0, Math.floor(y - paddingPixels))
    const clippedRight = Math.min(width, Math.ceil(right + paddingPixels))
    const clippedBottom = Math.min(height, Math.ceil(bottom + paddingPixels))
    return {
      x: left,
      y: top,
      width: Math.max(0, clippedRight - left),
      height: Math.max(0, clippedBottom - top),
    }
  }).filter(rect => rect.width > 0 && rect.height > 0)
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
  maskPaddingPixels = 2,
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
    const maskRects = previewMaskRects(
      quads, bounds, canvas.width, canvas.height, maskPaddingPixels,
    )
    if (!maskRects.length) return null
    context.fillStyle = '#fff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.save()
    context.beginPath()
    maskRects.forEach(rect => context.rect(rect.x, rect.y, rect.width, rect.height))
    context.clip()
    context.drawImage(
      sourceCanvas,
      sourceX, sourceY, sourceWidth, sourceHeight,
      0, 0, canvas.width, canvas.height,
    )
    context.restore()
    return {
      dataUrl: canvas.toDataURL('image/png'),
      width: canvas.width,
      height: canvas.height,
      bounds,
      masked: true,
    }
  } catch {
    return null
  }
}
