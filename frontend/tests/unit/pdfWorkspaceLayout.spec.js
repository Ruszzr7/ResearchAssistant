import { describe, expect, it } from 'vitest'
import {
  DEFAULT_WORKBENCH_RATIO,
  PDF_WORKBENCH_WIDTH_KEY,
  completePdfPaneWidth,
  normalizeWorkbenchRatio,
  ratioFromDividerPosition,
  readWorkbenchRatio,
  workbenchWidthForContainer,
  writeWorkbenchRatio,
} from '@/utils/pdfWorkspaceLayout.js'

describe('PDF workbench split layout', () => {
  it('uses a 60/40 default while preserving practical desktop minimums', () => {
    expect(workbenchWidthForContainer(1200)).toBe(477)
    expect(workbenchWidthForContainer(900, 0.65)).toBe(412)
    expect(workbenchWidthForContainer(600, 0.2)).toBe(266)
  })

  it('stops leftward dragging where a complete 100% PDF page still fits', () => {
    const minimumPdfWidth = completePdfPaneWidth(918, 22)
    expect(minimumPdfWidth).toBe(940)
    expect(workbenchWidthForContainer(1920, 0.65, minimumPdfWidth)).toBe(972)
    expect(1920 - 8 - workbenchWidthForContainer(1920, 0.65, minimumPdfWidth)).toBe(940)
    expect(workbenchWidthForContainer(1200, 0.65, minimumPdfWidth)).toBe(252)
  })

  it('maps divider movement to a bounded right-pane ratio', () => {
    const rect = { width: 1000, right: 1100 }
    expect(ratioFromDividerPosition(700, rect)).toBe(0.4)
    expect(ratioFromDividerPosition(50, rect)).toBe(0.65)
    expect(ratioFromDividerPosition(1000, rect)).toBe(0.2)
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
