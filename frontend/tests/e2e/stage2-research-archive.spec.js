import { expect, test } from '@playwright/test'
import { installPaperFlowMock } from './fixtures/paperFlowMock.js'

test.describe('阶段 2：研究档案证据复现', () => {
  test('历史回答可查看完整依据、跳转正文和公式并访问分页会话', async ({ page }) => {
    test.setTimeout(60_000)
    const state = await installPaperFlowMock(page)

    await page.goto('/library', { waitUntil: 'domcontentloaded' })
    await page.locator('.toolbar-paper-actions .el-button').first().click()
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: '导入论文' })
    await dialog.locator('input[type="file"]').setInputFiles({
      name: 'phase0-fixture.pdf', mimeType: 'application/pdf', buffer: state.pdf,
    })
    await page.locator('.el-dialog:visible .import-preview input').first().fill('阶段 0 固定论文')
    await dialog.getByRole('button', { name: '导入论文' }).click()
    await page.getByText('阶段 0 固定论文', { exact: true }).first().dblclick()
    await expect(page.locator('.pdf-page').first()).toBeVisible({ timeout: 30_000 })

    const composer = page.locator('.assistant-composer__input textarea')
    await composer.fill('论文的核心创新点是什么？')
    await page.getByRole('button', { name: '发送' }).click()
    await expect(page.locator('.chat-message.is-assistant .answer-text')).toContainText('核心创新')

    await page.goto('/archive', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.conversation-card')).toHaveCount(20)
    await expect(page.locator('.archive-pagination')).toContainText('共 21 个对话')
    await page.locator('.conversation-card').first().click()
    const detail = page.locator('.archive-detail')
    await expect(detail).toBeVisible()
    const sources = detail.locator('.evidence-source-list')
    await sources.locator('summary').click()
    await expect(sources.locator('.evidence-source__excerpt').first()).toContainText('The second physical line')
    await expect(sources.locator('.evidence-source__excerpt').first()).toContainText('complete source sentence')

    await sources.locator('.evidence-source__jump').first().click()
    await expect(page).toHaveURL(/\/research\/184\?.*page=1/)
    await expect(page.locator('.evidence-focus-preview polygon')).toHaveCount(2, { timeout: 10_000 })

    await page.goto('/archive', { waitUntil: 'domcontentloaded' })
    await page.locator('.conversation-card').first().click()
    const formulaSources = page.locator('.archive-detail .evidence-source-list')
    await formulaSources.locator('summary').click()
    await formulaSources.locator('.evidence-source__jump').nth(1).click()
    await expect(page).toHaveURL(/\/research\/184\?.*page=1/)
    await expect(page.locator('.evidence-focus-preview polygon')).toHaveAttribute('fill', '#ffeb3b', { timeout: 10_000 })

    await page.goto('/archive', { waitUntil: 'domcontentloaded' })
    await page.locator('.archive-pagination .btn-next').click()
    await expect(page.locator('.conversation-card')).toHaveCount(1)
    await expect(page.locator('.conversation-card').first()).toContainText('阶段 2 历史对话 21')
    await page.locator('.conversation-card').first().click()
    await expect(page.locator('.archive-detail')).toContainText('核心创新')
  })
})
