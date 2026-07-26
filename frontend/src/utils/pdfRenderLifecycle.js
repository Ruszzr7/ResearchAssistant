/** Marks a materialized page surface for deterministic repaint after KeepAlive activation. */
export function invalidatePageRenderSurface(page) {
  if (!page) return
  page.surfaceVersion = Number(page.surfaceVersion || 0) + 1
  page.rendered = false
  page.canvasReady = false
  page.renderFailed = false
  page.interactionFailed = false
}

export function pageRenderSurfaceIsUsable(page, canvas, textLayer, dpr = 1) {
  if (!page?.rendered || !page.canvasReady || page.renderFailed) return false
  if (!canvas?.isConnected || !textLayer?.isConnected) return false
  const expectedWidth = Math.max(1, Math.floor(Number(page.width || 0) * dpr))
  const expectedHeight = Math.max(1, Math.floor(Number(page.height || 0) * dpr))
  return canvas.width === expectedWidth && canvas.height === expectedHeight
}
