import { describe, expect, it } from 'vitest'
import {
  DEFAULT_WORKBENCH_RATIO,
  MIN_PDF_TOOLBAR_WIDTH,
  PDF_WORKBENCH_WIDTH_KEY,
  completePdfPaneWidth,
  normalizeWorkbenchRatio,
  ratioFromDividerPosition,
  readWorkbenchRatio,
  splitWorkbenchAllocation,
  workbenchWidthForContainer,
  writeWorkbenchRatio,
} from '@/utils/pdfWorkspaceLayout.js'

describe('PDF workbench split layout', () => {
  it('uses a 60/40 default while preserving practical desktop minimums', () => {
    expect(workbenchWidthForContainer(1200)).toBe(477)
    expect(workbenchWidthForContainer(900, 0.65)).toBe(412)
    expect(workbenchWidthForContainer(600, 0.2)).toBe(266)
  })

  it('keeps the approved assistant ratio and lets wide PDF pages scroll when needed', () => {
    const minimumPdfWidth = completePdfPaneWidth(918, 22)
    expect(minimumPdfWidth).toBe(940)
    expect(workbenchWidthForContainer(1920, 0.65, minimumPdfWidth)).toBe(972)
    expect(1920 - 8 - workbenchWidthForContainer(1920, 0.65, minimumPdfWidth)).toBe(940)
    expect(workbenchWidthForContainer(1200, 0.65, minimumPdfWidth)).toBe(320)
  })

  it('reserves enough width for the complete PDF toolbar even before a page is measured', () => {
    expect(completePdfPaneWidth(0)).toBe(MIN_PDF_TOOLBAR_WIDTH)
    expect(completePdfPaneWidth(600, 20)).toBe(MIN_PDF_TOOLBAR_WIDTH)
  })

  it('maps divider movement to a bounded right-pane ratio', () => {
    const rect = { width: 1000, right: 1100 }
    expect(ratioFromDividerPosition(700, rect)).toBe(0.4)
    expect(ratioFromDividerPosition(50, rect)).toBe(0.65)
    expect(ratioFromDividerPosition(1000, rect)).toBe(0.2)
  })

  it('takes the comment list out of the assistant allocation instead of the PDF pane', () => {
    expect(splitWorkbenchAllocation(600, false)).toEqual({ commentWidth: 0, assistantWidth: 600 })
    expect(splitWorkbenchAllocation(600, true)).toEqual({ commentWidth: 280, assistantWidth: 320 })
    expect(splitWorkbenchAllocation(477, true)).toEqual({ commentWidth: 237, assistantWidth: 240 })
    expect(splitWorkbenchAllocation(300, true)).toEqual({ commentWidth: 135, assistantWidth: 165 })
  })

  it('persists only normalized ratios and safely handles missing or invalid storage', () => {
    const values = new Map()
    const storage = {
      getItem: key => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
    }

    expect(readWorkbenchRatio(storage)).toBe(DEFAULT_WORKBENCH_RATIO)
    expect(writeWorkbenchRatio(0.9, storage)).toBe(0.65)
    expect(values.get(PDF_WORKBENCH_WIDTH_KEY)).toBe('0.65')
    values.set(PDF_WORKBENCH_WIDTH_KEY, 'broken')
    expect(readWorkbenchRatio(storage)).toBe(DEFAULT_WORKBENCH_RATIO)
    expect(normalizeWorkbenchRatio(0.1)).toBe(0.2)
  })
})
