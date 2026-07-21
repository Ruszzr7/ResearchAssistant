import { describe, expect, it } from 'vitest'
import {
  annotationDisplayColor,
  buildSelectionCommentDraft,
  buildSelectionNoteDraft,
  isMarkerAnnotation,
  isCommentAnnotation,
  isSelectionNote,
  resizeTextAnnotationQuads,
} from '@/utils/pdfAnnotation.js'

const multiLineQuads = [
  { x1: 0.2, y1: 0.4, x2: 0.8, y2: 0.4, x3: 0.8, y3: 0.36, x4: 0.2, y4: 0.36 },
  { x1: 0.1, y1: 0.48, x2: 0.5, y2: 0.48, x3: 0.5, y3: 0.44, x4: 0.1, y4: 0.44 }
]

describe('PDF text annotation range resizing', () => {
  it('moves only the first quad start handle for a multi-line selection', () => {
    const result = resizeTextAnnotationQuads(multiLineQuads, 'start', 0.35)

    expect(result.changed).toBe(true)
    expect(result.quads[0].x1).toBe(0.35)
    expect(result.quads[0].x4).toBe(0.35)
    expect(result.quads[1]).toEqual(multiLineQuads[1])
    expect(multiLineQuads[0].x1).toBe(0.2)
  })

  it('keeps a minimum width when an end handle is dragged past its start', () => {
    const result = resizeTextAnnotationQuads(multiLineQuads, 'end', 0.02)

    expect(result.changed).toBe(true)
    expect(result.quads[1].x2).toBeCloseTo(0.106)
    expect(result.quads[1].x3).toBeCloseTo(0.106)
  })
})

describe('PDF selection note draft', () => {
  it('binds a selection note to the exact selected quads and text', () => {
    const draft = buildSelectionNoteDraft({
      localId: 12,
      paperId: 7,
      color: '#2196f3',
      selection: {
        text: 'Selected formula explanation',
        groups: [{
          pageNum: 3,
          pageState: { viewport: { width: 600, height: 800, rotation: 0, scale: 1.25 } },
          quads: multiLineQuads,
        }],
      },
      notePosition: { x: 0.9, y: 0.4 },
    })

    expect(draft).toMatchObject({
      localId: 12,
      paperId: 7,
      type: 'NOTE',
      page: 3,
      color: '#2196f3',
      coordinates: {
        anchorKind: 'SELECTION',
        anchorText: 'Selected formula explanation',
        notePosition: { x: 0.9, y: 0.4 },
        pageWidth: 600,
        pageHeight: 800,
      },
    })
    expect(draft.coordinates.anchorQuads).toEqual(multiLineQuads)
    expect(draft.coordinates.anchorQuads).not.toBe(multiLineQuads)
  })

  it('rejects a selection without usable geometry', () => {
    expect(buildSelectionNoteDraft({ selection: { groups: [] } })).toBeNull()
  })
})

describe('PDF selection comment draft', () => {
  it('binds a comment to selected text while keeping its marker draggable', () => {
    const draft = buildSelectionCommentDraft({
      localId: 13,
      paperId: 7,
      color: '#f44336',
      selection: {
        text: 'Claim under review',
        groups: [{
          pageNum: 4,
          pageState: { viewport: { width: 600, height: 800, rotation: 0, scale: 1.5 } },
          quads: multiLineQuads,
        }],
      },
      notePosition: { x: 0.84, y: 0.45 },
    })

    expect(draft).toMatchObject({
      type: 'COMMENT',
      page: 4,
      completed: false,
      coordinates: {
        anchorKind: 'SELECTION',
        anchorText: 'Claim under review',
        notePosition: { x: 0.84, y: 0.45 },
      },
    })
    expect(draft.coordinates.anchorQuads).toEqual(multiLineQuads)
    expect(isMarkerAnnotation(draft)).toBe(true)
    expect(isCommentAnnotation(draft)).toBe(true)
    expect(isSelectionNote(draft)).toBe(false)
  })

  it('uses green as the display color only after a comment is completed', () => {
    expect(annotationDisplayColor({ type: 'COMMENT', color: '#f44336', completed: true })).toBe('#4caf50')
    expect(annotationDisplayColor({ type: 'NOTE', color: '#2196f3', completed: true })).toBe('#2196f3')
  })

  it('persists an independent copy of the exact text anchor', () => {
    const textAnchor = {
      version: 1,
      page: 2,
      documentFingerprint: 'fingerprint',
      textMapVersion: 1,
      ranges: [{ itemIndex: 8, spanIndex: 7, startOffset: 2, endOffset: 6 }],
    }
    const draft = buildSelectionCommentDraft({
      localId: 1,
      paperId: 9,
      color: '#f44336',
      selection: {
        text: 'text',
        textAnchor,
        groups: [{
          pageNum: 2,
          pageState: { viewport: { width: 600, height: 800, rotation: 0, scale: 1.5 } },
          quads: multiLineQuads,
        }],
      },
      notePosition: { x: 0.3, y: 0.4 },
    })

    expect(draft.coordinates.textAnchor).toEqual(textAnchor)
    expect(draft.coordinates.textAnchor).not.toBe(textAnchor)
  })
})
