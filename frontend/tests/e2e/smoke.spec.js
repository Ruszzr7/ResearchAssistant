import { test, expect } from '@playwright/test'

test('loads the ResearchAssistant shell', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(page).toHaveTitle('Research Assistant')
  await expect(page.locator('#app')).toBeVisible()
})
