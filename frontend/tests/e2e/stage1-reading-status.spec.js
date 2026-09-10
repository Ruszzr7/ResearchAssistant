import { expect, test } from '@playwright/test'
import { installPaperFlowMock } from './fixtures/paperFlowMock.js'

async function importFixture(page, state) {
  await page.goto('/library', { waitUntil: 'domcontentloaded' })
  await page.locator('.toolbar-paper-actions .el-button').first().click()
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '导入论文' })
  await dialog.locator('input[type="file"][accept=".pdf"]').setInputFiles({
    name: 'phase1-fixture.pdf',
    mimeType: 'application/pdf',
    buffer: state.pdf,
  })
  await page.locator('.el-dialog:visible .import-preview input').first().fill('阶段 0 固定论文')
  await dialog.getByRole('button', { name: '导入论文' }).click()
  await expect(page.getByText('阶段 0 固定论文', { exact: true })).toBeVisible()
}

test.describe('阶段 1：阅读状态与看板', () => {
  test('首次成功打开进入正读，手动标记已读后跨页面保持', async ({ page }) => {
    test.setTimeout(60_000)
    const state = await installPaperFlowMock(page)

    await importFixture(page, state)
    await page.getByText('阶段 0 固定论文', { exact: true }).first().dblclick()
    await expect(page.locator('.pdf-page').first()).toBeVisible({ timeout: 30_000 })
    await expect.poll(() => state.paper.readingStatus).toBe('READING')

    await page.getByRole('button', { name: '关闭' }).click()
    await expect(page).toHaveURL(/\/library/)
    await expect(page.locator('.status-tag')).toContainText('正读')

    await page.locator('.status-tag').click()
    await page.locator('.el-dropdown-menu:visible').getByText('已读', { exact: true }).click()
    await expect.poll(() => state.paper.readingStatus).toBe('READ')
    await expect(page.locator('.status-tag')).toContainText('已读')

    await page.goto('/', { waitUntil: 'domcontentloaded' })
    const readCard = page.locator('.stat-cell').filter({ hasText: '已读' })
    await expect(readCard).toContainText('1')
    const uncategorizedCard = page.locator('.stat-cell').filter({ hasText: '未分类' })
    await expect(uncategorizedCard).toContainText('1')

    await page.goto('/library', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.status-tag')).toContainText('已读')
    await page.getByText('阶段 0 固定论文', { exact: true }).first().dblclick()
    await expect(page.locator('.pdf-page').first()).toBeVisible({ timeout: 30_000 })
    await expect.poll(() => state.paper.readingStatus).toBe('READ')
  })
})
