import { describe, expect, it } from 'vitest'
import {
  invalidatePageRenderSurface,
  pageRenderSurfaceIsUsable,
} from '@/utils/pdfRenderLifecycle.js'

describe('PDF render lifecycle recovery', () => {
  it('recognizes a current connected canvas surface', () => {
    const page = {
      rendered: true,
      canvasReady: true,
      renderFailed: false,
      width: 600,
      height: 800,
    }
    const canvas = { isConnected: true, width: 1200, height: 1600 }
    const textLayer = { isConnected: true }

    expect(pageRenderSurfaceIsUsable(page, canvas, textLayer, 2)).toBe(true)
    canvas.width = 0
    expect(pageRenderSurfaceIsUsable(page, canvas, textLayer, 2)).toBe(false)
  })

  it('invalidates stale visual and interaction state for a repaint', () => {
    const page = {
      surfaceVersion: 3,
      rendered: true,
      canvasReady: true,
      renderFailed: true,
      interactionFailed: true,
    }

    invalidatePageRenderSurface(page)

    expect(page).toEqual({
      surfaceVersion: 4,
      rendered: false,
      canvasReady: false,
      renderFailed: false,
      interactionFailed: false,
    })
  })
})
