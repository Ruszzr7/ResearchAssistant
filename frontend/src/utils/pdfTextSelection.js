/**
 * Geometry helpers for deciding whether a pointer can start a native PDF text
 * selection. PDF.js exposes text as absolutely positioned spans, so the
 * browser should only start selection on a real glyph box (with a small
 * tolerance for anti-aliased / sub-pixel edges).
 */
export const PDF_TEXT_HIT_SLOP = 4

export function distanceToRect(x, y, rect) {
  if (!Number.isFinite(x) || !Number.isFinite(y) || !rect) return Number.POSITIVE_INFINITY

  const left = Number(rect.left)
  const right = Number(rect.right)
  const top = Number(rect.top)
  const bottom = Number(rect.bottom)
  if (![left, right, top, bottom].every(Number.isFinite) || right < left || bottom < top) {
    return Number.POSITIVE_INFINITY
  }

  const horizontal = x < left ? left - x : (x > right ? x - right : 0)
  const vertical = y < top ? top - y : (y > bottom ? y - bottom : 0)
  return Math.hypot(horizontal, vertical)
}

export function isNearTextRect(x, y, rect, hitSlop = PDF_TEXT_HIT_SLOP) {
  return distanceToRect(x, y, rect) <= hitSlop
}
