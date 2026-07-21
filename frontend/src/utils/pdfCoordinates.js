const EPSILON = 1e-9

export function normalizeViewportRectangle(rectangle, viewport) {
  const width = positiveNumber(viewport?.width)
  const height = positiveNumber(viewport?.height)
  if (!rectangle || !width || !height) return null

  const left = clamp(Number(rectangle.x) || 0, 0, width)
  const top = clamp(Number(rectangle.y) || 0, 0, height)
  const right = clamp(left + Math.max(0, Number(rectangle.width) || 0), 0, width)
  const bottom = clamp(top + Math.max(0, Number(rectangle.height) || 0), 0, height)
  if (right - left <= EPSILON || bottom - top <= EPSILON) return null
  return {
    x: left / width,
    y: top / height,
    width: (right - left) / width,
    height: (bottom - top) / height,
  }
}

export function denormalizeViewportRectangle(box, viewport) {
  const width = positiveNumber(viewport?.width)
  const height = positiveNumber(viewport?.height)
  if (!box || !width || !height) return null
  const x = clamp(Number(box.x) || 0, 0, 1)
  const y = clamp(Number(box.y) || 0, 0, 1)
  const right = clamp(x + Math.max(0, Number(box.width) || 0), 0, 1)
  const bottom = clamp(y + Math.max(0, Number(box.height) || 0), 0, 1)
  if (right - x <= EPSILON || bottom - y <= EPSILON) return null
  return {
    x: x * width,
    y: y * height,
    width: (right - x) * width,
    height: (bottom - y) * height,
  }
}

export function clientRectangleToViewportRectangle(rectangle, pageRectangle) {
  if (!rectangle || !pageRectangle) return null
  return {
    x: Number(rectangle.left) - Number(pageRectangle.left),
    y: Number(rectangle.top) - Number(pageRectangle.top),
    width: Number(rectangle.width),
    height: Number(rectangle.height),
  }
}

export function rectangleRoundtripError(first, second) {
  if (!first || !second) return Number.POSITIVE_INFINITY
  return Math.max(
    Math.abs(Number(first.x) - Number(second.x)),
    Math.abs(Number(first.y) - Number(second.y)),
    Math.abs(Number(first.width) - Number(second.width)),
    Math.abs(Number(first.height) - Number(second.height)),
  )
}

function positiveNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : null
}

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value))
}
