import { expect, test } from '@playwright/test'
import { installPaperFlowMock } from './fixtures/paperFlowMock.js'

test.describe('阶段 0：论文阅读核心链路', () => {
  test('导入、提问、证据跳转与刷新恢复可重复完成', async ({ page }, testInfo) => {
    test.setTimeout(60_000)
    const consoleErrors = []
    page.on('console', message => {
      if (message.type() === 'error') consoleErrors.push(`[console] ${message.text()}`)
    })
    page.on('pageerror', error => consoleErrors.push(`[pageerror] ${error.message}`))
    const state = await installPaperFlowMock(page)

    await page.goto('/library', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveTitle('Research Assistant')

    const importButton = page.locator('.toolbar-paper-actions .el-button').first()
    await expect(importButton).toBeVisible()
    await importButton.click()
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: '导入论文' })
    await expect(dialog).toBeVisible()
    await page.locator('input[type="file"]').setInputFiles({
      name: 'phase0-fixture.pdf',
      mimeType: 'application/pdf',
      buffer: state.pdf,
    })
    await page.locator('.el-dialog:visible .import-preview input').first().fill('阶段 0 固定论文')
    await dialog.getByRole('button', { name: '导入论文' }).click()
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).toBeVisible()
    await expect.poll(() => state.requests.some(item => (
      item.path === '/research-automation/task/phase0-import-task' && item.method === 'GET'
    ))).toBe(true)

    // PaperTable opens a PDF on a title-cell double click. Keep this as a real UI
    // transition so the baseline protects the library-to-reader handoff as well.
    const titleCell = page.getByText('阶段 0 固定论文', { exact: true }).first()
    await titleCell.dblclick({ timeout: 5_000 })
    await expect(page).toHaveURL(/\/research\/184/)

    await expect(page.locator('.pdf-page').first()).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.paper-workbench')).toBeVisible()
    await expect(page.locator('.memory-status')).toContainText('论文理解已就绪')

    const composer = page.locator('.assistant-composer__input textarea')
    await expect(composer).toBeEnabled()
    await composer.fill('论文的核心创新点是什么？')
    await page.getByRole('button', { name: '发送' }).click()

    await expect(page.locator('.chat-message.is-assistant .answer-text')).toContainText('核心创新')
    const claims = page.locator('.chat-claim-list').last()
    await expect(claims).toContainText('查看依据（2）')
    await claims.getByText('查看依据（2）').click()
    await expect(claims.locator('.evidence-source__excerpt')).toContainText('The second physical line')
    await expect(claims.locator('.evidence-source__jump').first()).toContainText('正文 · p.1')

    await claims.locator('.evidence-source__jump').first().click()
    await expect(page.locator('.evidence-focus-preview polygon')).toHaveCount(2, { timeout: 10_000 })

    await claims.locator('.evidence-source__jump').nth(1).click()
    await expect(claims.locator('.evidence-source__formula')).toContainText('x =')
    await expect(page.locator('.evidence-focus-preview polygon')).toHaveCount(1, { timeout: 10_000 })
    await expect(page.locator('.evidence-focus-preview polygon')).toHaveAttribute('fill', '#ffeb3b')

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('.paper-workbench')).toBeVisible()
    await expect(page.locator('.chat-message.is-assistant .answer-text')).toContainText('核心创新')
    const restoredClaims = page.locator('.chat-claim-list').last()
    await restoredClaims.getByText('查看依据（2）').click()
    await expect(restoredClaims.locator('.evidence-source__excerpt')).toContainText('The second physical line')
    await expect(restoredClaims.locator('.evidence-source__formula')).toContainText('x =')

    await testInfo.attach('phase0-api-trajectory', {
      body: JSON.stringify({ requests: state.requests, blockedRequests: state.blockedRequests }, null, 2),
      contentType: 'application/json',
    })
    await testInfo.attach('phase0-console-errors', {
      body: consoleErrors.join('\n') || '无控制台错误',
      contentType: 'text/plain',
    })
    expect(state.blockedRequests).toEqual([])
    expect(consoleErrors).toEqual([])
    expect(state.agentTrajectory.map(item => item.step)).toEqual([
      'load_profile', 'retrieve_evidence', 'submit_answer',
    ])
    expect(state.agentTrajectory[1].needs[0]).toMatchObject({
      id: 'core-innovation',
      objective: '确认论文的核心创新点',
    })
    expect(state.agentTrajectory[2].sourceObjectIds).toEqual([
      'phase0-source-1', 'phase0-formula-1',
    ])
    expect(state.requests.filter(item => item.path === '/agent/turns' && item.method === 'POST')).toHaveLength(1)
    expect(state.requests.some(item => item.path === '/agent/turns' && item.method === 'POST')).toBe(true)
    expect(state.requests.some(item => item.path === `/papers/184/pdf` && item.method === 'GET')).toBe(true)
    expect(state.requests.some(item => item.path === '/research-automation/task/phase0-import-task' && item.method === 'GET')).toBe(true)
  })
})
