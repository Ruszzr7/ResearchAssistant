import { createPdfInteractionEngine } from '/src/services/pdfiumInteractionEngine.js'
import { segmentPdfSelection } from '/src/utils/pdfContentSegments.js'

const output = document.querySelector('#result')
const pdfUrl = new URLSearchParams(location.search).get('pdf')

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
