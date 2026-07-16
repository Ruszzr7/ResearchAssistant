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
  return {
    x: left / pageRect.width,
    y: top / pageRect.height,
    width: width / pageRect.width,
    height: height / pageRect.height,
  }
}

export function formulaRegionSvgRect(box, pageWidth, pageHeight) {
  if (!box || !pageWidth || !pageHeight) return null
  return {
    x: box.x * pageWidth,
    y: box.y * pageHeight,
    width: box.width * pageWidth,
    height: box.height * pageHeight,
  }
}
