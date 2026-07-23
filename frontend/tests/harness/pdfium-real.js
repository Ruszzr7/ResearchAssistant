import * as pdfjsLib from 'pdfjs-dist'
import pdfjsWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { createPdfInteractionEngine } from '/src/services/pdfiumInteractionEngine.js'
import { segmentPdfSelection } from '/src/utils/pdfContentSegments.js'
import { normalizePdfSelectionText } from '/src/utils/pdfSelectionText.js'
import { boundingBoxToViewportQuad } from '/src/utils/pdfSelectionAnchor.js'
import { createPdfSelectionPreview } from '/src/utils/pdfSelectionPreview.js'

const output = document.querySelector('#result')
const pdfUrl = new URLSearchParams(location.search).get('pdf')
pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl

async function renderSelectionPreview(selection) {
  const loadingTask = pdfjsLib.getDocument(pdfUrl)
  const pdfDocument = await loadingTask.promise
  try {
    const page = await pdfDocument.getPage(selection.pageIndex + 1)
    const viewport = page.getViewport({ scale: 1.5 })
    const canvas = document.createElement('canvas')
    canvas.width = Math.ceil(viewport.width)
    canvas.height = Math.ceil(viewport.height)
    await page.render({ canvasContext: canvas.getContext('2d'), viewport }).promise
    return createPdfSelectionPreview(
      canvas,
      selection.rects.map(boundingBoxToViewportQuad).filter(Boolean),
    )
  } finally {
    await pdfDocument.destroy()
  }
}

async function run() {
  if (!pdfUrl) throw new Error('Missing pdf query parameter')
  const engine = createPdfInteractionEngine()
  try {
    const documentInfo = await engine.open({ id: 'real-regression', url: pdfUrl })
    const coefficient = await engine.search('global power coefficient')
    const precoders = await engine.search('precoders')
    const pageThreeMatch = precoders.find(match => match.pageIndex === 2)
    const selected = pageThreeMatch
      ? await engine.select(2, pageThreeMatch.charStart, pageThreeMatch.charEnd)
      : null
    const denseSelection = pageThreeMatch
      ? await engine.select(2, Math.max(0, pageThreeMatch.charStart - 25), pageThreeMatch.charEnd + 600)
      : null
    const denseSegments = segmentPdfSelection(denseSelection?.runs, denseSelection?.pageSize)
    const denseText = normalizePdfSelectionText(denseSelection?.text)
    const densePreview = denseSelection ? await renderSelectionPreview(denseSelection) : null
    if (densePreview?.dataUrl) {
      const image = document.createElement('img')
      image.id = 'selection-preview'
      image.alt = 'PDF real selection preview'
      image.src = densePreview.dataUrl
      document.body.append(image)
      await image.decode()
    }
    const hitRect = pageThreeMatch?.rects?.[0]
    const hit = hitRect ? await engine.hitTest(2, {
      x: hitRect.x + hitRect.width / 2,
      y: hitRect.y + hitRect.height / 2,
    }) : null
    return {
      pageCount: documentInfo.pageCount,
      coefficientPages: [...new Set(coefficient.map(match => match.pageIndex + 1))],
      pageThreePrecoderText: selected?.text || '',
      pageThreePrecoderRects: selected?.rects?.length || 0,
      pageThreeDenseMathSegments: denseSegments.filter(segment => segment.type !== 'TEXT').length,
      pageThreeDenseHasCoefficient: /global power coefficient/i.test(denseSelection?.text || ''),
      pageThreeReadableHasCoefficient: /global power coefficient/i.test(denseText.readableText),
      pageThreeReadableHasLineBreak: /[\r\n]/u.test(denseText.readableText),
      pageThreeReadableHasIllegalCharacter: /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\ufffd\ue000-\uf8ff]/u
        .test(denseText.readableText),
      pageThreePreviewWidth: densePreview?.width || 0,
      pageThreePreviewHeight: densePreview?.height || 0,
      pageThreeDenseMinimumX: Math.min(...(denseSelection?.rects || []).map(rect => rect.x)),
      pageThreeHitDelta: hit ? Math.abs(hit.charIndex - pageThreeMatch.charStart) : null,
    }
  } finally {
    await engine.close()
  }
}

run().then(result => {
  output.textContent = JSON.stringify(result)
  document.documentElement.dataset.status = 'ready'
}).catch(error => {
  output.textContent = error?.stack || String(error)
  document.documentElement.dataset.status = 'failed'
})
