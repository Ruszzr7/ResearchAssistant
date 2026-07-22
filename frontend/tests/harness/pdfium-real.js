import { createPdfInteractionEngine } from '/src/services/pdfiumInteractionEngine.js'

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
    return {
      pageCount: documentInfo.pageCount,
      coefficientPages: [...new Set(coefficient.map(match => match.pageIndex + 1))],
      pageThreePrecoderText: selected?.text || '',
      pageThreePrecoderRects: selected?.rects?.length || 0,
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
