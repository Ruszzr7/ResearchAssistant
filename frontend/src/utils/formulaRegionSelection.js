import {
  denormalizeViewportRectangle,
  normalizeViewportRectangle,
} from '@/utils/pdfCoordinates.js'

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value))
}

/** Converts a drag in CSS pixels into a zoom-independent top-left normalized box. */
export function normalizedFormulaRegion(start, end, pageRect, minimumPixels = 8) {
  if (!start || !end || !pageRect || pageRect.width <= 0 || pageRect.height <= 0) return null
  const startX = clamp(start.x - pageRect.left, 0, pageRect.width)
  const startY = clamp(start.y - pageRect.top, 0, pageRect.height)
  const endX = clamp(end.x - pageRect.left, 0, pageRect.width)
  const endY = clamp(end.y - pageRect.top, 0, pageRect.height)
  const left = Math.min(startX, endX)
  const top = Math.min(startY, endY)
  const width = Math.abs(endX - startX)
  const height = Math.abs(endY - startY)
  if (width < minimumPixels || height < minimumPixels) return null
  return normalizeViewportRectangle({ x: left, y: top, width, height }, pageRect)
}

export function formulaRegionSvgRect(box, pageWidth, pageHeight) {
  return denormalizeViewportRectangle(box, { width: pageWidth, height: pageHeight })
}

/** Creates a bounded local preview/model input from the already-painted PDF.js canvas. */
export function createFormulaRegionPreview(sourceCanvas, box, {
  maxWidth = 1200,
  maxHeight = 800,
  paddingPixels = 8,
  canvasFactory = () => document.createElement('canvas'),
} = {}) {
  try {
    if (!sourceCanvas?.width || !sourceCanvas?.height || !box) return null
    const left = Math.max(0, Math.floor(box.x * sourceCanvas.width) - paddingPixels)
    const top = Math.max(0, Math.floor(box.y * sourceCanvas.height) - paddingPixels)
    const right = Math.min(sourceCanvas.width,
      Math.ceil((box.x + box.width) * sourceCanvas.width) + paddingPixels)
    const bottom = Math.min(sourceCanvas.height,
      Math.ceil((box.y + box.height) * sourceCanvas.height) + paddingPixels)
    const sourceWidth = right - left
    const sourceHeight = bottom - top
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    const scale = Math.min(1, maxWidth / sourceWidth, maxHeight / sourceHeight)
    const canvas = canvasFactory()
    canvas.width = Math.max(1, Math.round(sourceWidth * scale))
    canvas.height = Math.max(1, Math.round(sourceHeight * scale))
    const context = canvas.getContext?.('2d')
    if (!context) return null
    context.fillStyle = '#fff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(
      sourceCanvas,
      left, top, sourceWidth, sourceHeight,
      0, 0, canvas.width, canvas.height,
    )
    return {
      dataUrl: canvas.toDataURL('image/png'),
      width: canvas.width,
      height: canvas.height,
    }
  } catch {
    return null
  }
}
