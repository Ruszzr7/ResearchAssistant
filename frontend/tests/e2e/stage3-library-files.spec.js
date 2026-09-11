import { expect, test } from '@playwright/test'
import { installPaperFlowMock } from './fixtures/paperFlowMock.js'

async function openImportDialog(page) {
  await page.locator('.toolbar-paper-actions .el-button').first().click()
  const dialog = page.locator('.el-dialog:visible').filter({ hasText: '导入论文' })
  await expect(dialog).toBeVisible()
  return dialog
}

async function importFixture(page, state, title = '阶段 0 固定论文') {
  const dialog = await openImportDialog(page)
  await dialog.locator('input[type="file"]').setInputFiles({
    name: 'phase3-fixture.pdf', mimeType: 'application/pdf', buffer: state.pdf,
  })
  await dialog.locator('.import-preview input').first().fill(title)
  await dialog.getByRole('button', { name: '导入论文' }).click()
}

test.describe('阶段 3：文库文件与数据一致性', () => {
  test('非法 PDF 显示明确错误且不会产生幽灵论文', async ({ page }) => {
    test.setTimeout(60_000)
    const state = await installPaperFlowMock(page, { invalidUploadOnce: true })

    await page.goto('/library', { waitUntil: 'domcontentloaded' })
    await importFixture(page, state)
    await expect(page.getByText('操作失败：文件不是有效的 PDF，或 PDF 无法打开', { exact: true })).toBeVisible()
    await expect.poll(() => state.imported).toBe(false)
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).not.toBeVisible()

    // 同一导入窗口重试，验证失败后状态仍可恢复且不会留下第二条记录。
    await page.locator('.el-dialog:visible').getByRole('button', { name: '导入论文' }).click()
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).toBeVisible()
    await expect.poll(() => state.uploadAttempts).toBe(2)
  })

  test('覆盖失败时保留原论文和原文件状态', async ({ page }) => {
    test.setTimeout(60_000)
    const state = await installPaperFlowMock(page, { overwriteFailure: true })

    await page.goto('/library', { waitUntil: 'domcontentloaded' })
    await importFixture(page, state)
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).toBeVisible()

    await importFixture(page, state, '替换标题')
    await expect(page.getByRole('button', { name: '覆盖', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '覆盖', exact: true }).click()
    await expect(page.getByText('覆盖失败：数据库写入失败', { exact: true })).toBeVisible()
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).toBeVisible()
    await expect.poll(() => state.imported).toBe(true)
  })

  test('删除论文后文库列表和本地状态同时清空', async ({ page }) => {
    test.setTimeout(60_000)
    const state = await installPaperFlowMock(page)

    await page.goto('/library', { waitUntil: 'domcontentloaded' })
    await importFixture(page, state)
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).toBeVisible()

    const row = page.locator('.vxe-body--row').filter({ hasText: '阶段 0 固定论文' })
    await row.locator('.action-more').click()
    await page.locator('.el-dropdown-menu:visible').getByText('删除', { exact: true }).click()
    await page.getByRole('button', { name: '删除', exact: true }).last().click()

    await expect.poll(() => state.imported).toBe(false)
    await expect(page.getByText('阶段 0 固定论文', { exact: true })).not.toBeVisible()
  })
})
