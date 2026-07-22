import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { chromium } from '@playwright/test'

const sample = process.env.RA_PDF_INTERACTION_SAMPLE
if (!sample) throw new Error('Set RA_PDF_INTERACTION_SAMPLE to a private real PDF')

const origin = 'http://127.0.0.1:4173'
let server = null

async function serverIsReady() {
  try {
    return (await fetch(`${origin}/tests/harness/pdfium-real.html`)).ok
  } catch {
    return false
  }
}

if (!await serverIsReady()) {
  server = spawn(process.execPath, [
    'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '4173',
  ], { cwd: process.cwd(), stdio: 'ignore', windowsHide: true })
  for (let attempt = 0; attempt < 50 && !await serverIsReady(); attempt += 1) {
    await new Promise(resolve => setTimeout(resolve, 100))
  }
}
assert.equal(await serverIsReady(), true, 'Vite regression harness did not start')

const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
  || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const browser = await chromium.launch({ executablePath, headless: true })
try {
  const page = await browser.newPage()
  const absolutePath = path.resolve(sample).replaceAll('\\', '/')
  const pdfUrl = `/@fs/${absolutePath}`
  await page.goto(`${origin}/tests/harness/pdfium-real.html?pdf=${encodeURIComponent(pdfUrl)}`)
  await page.locator('html[data-status]').waitFor({ timeout: 60_000 })
  const status = await page.locator('html').getAttribute('data-status')
  const content = await page.locator('#result').textContent()
  assert.equal(status, 'ready', content)
  const result = JSON.parse(content)
  assert.equal(result.pageCount, 16)
  for (const expectedPage of [1, 3, 7, 10, 12]) {
    assert.ok(result.coefficientPages.includes(expectedPage), `missing search page ${expectedPage}`)
  }
  assert.match(result.pageThreePrecoderText, /precoders/i)
  assert.ok(result.pageThreePrecoderRects > 0)
  console.log(JSON.stringify(result))
} finally {
  await browser.close()
  server?.kill()
}
