const MINIMUM_RANGE_WIDTH = 0.006

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

/**
 * 调整文本批注的首端或末端矩形。
 *
 * 批注坐标始终使用归一化 x 值；跨行批注只改变首个/末个 quad，保留中间行，
 * 从而让用户能够安全地微调标记范围而不改写整段选择。
 */
export function resizeTextAnnotationQuads(quads, edge, pointerX) {
  if (!Array.isArray(quads) || quads.length === 0 || !['start', 'end'].includes(edge)) {
    return { quads, changed: false }
  }

  const nextQuads = quads.map(quad => ({ ...quad }))
  const index = edge === 'start' ? 0 : nextQuads.length - 1
  const target = nextQuads[index]
  const normalizedPointer = clamp(Number(pointerX), 0.002, 0.998)

  if (edge === 'start') {
    const right = Math.max(Number(target.x2), Number(target.x3))
    const next = Math.min(normalizedPointer, right - MINIMUM_RANGE_WIDTH)
    const changed = Math.abs(Number(target.x1) - next) > 0.001
      || Math.abs(Number(target.x4) - next) > 0.001
    if (!changed) return { quads, changed: false }
    target.x1 = next
    target.x4 = next
    return { quads: nextQuads, changed: true }
  }

  const left = Math.min(Number(target.x1), Number(target.x4))
  const next = Math.max(normalizedPointer, left + MINIMUM_RANGE_WIDTH)
  const changed = Math.abs(Number(target.x2) - next) > 0.001
    || Math.abs(Number(target.x3) - next) > 0.001
  if (!changed) return { quads, changed: false }
  target.x2 = next
  target.x3 = next
  return { quads: nextQuads, changed: true }
}

/**
 * 把当前 PDF 文本选区转换为用户手写批注草稿。
 *
 * 草稿仍使用 NOTE 类型，以复用可拖动 emoji、指向线和既有编辑/删除能力；
 * anchorKind 用于与页面任意位置创建的自由便签区分。
 */
export function buildSelectionNoteDraft({ localId, paperId, color, selection, notePosition }) {
  const group = selection?.groups?.[0]
  const viewport = group?.pageState?.viewport
  if (!viewport || !Array.isArray(group.quads) || group.quads.length === 0) return null

  const quads = group.quads.map(quad => ({ ...quad }))
  return {
    localId,
    paperId,
    type: 'NOTE',
    page: group.pageNum,
    color,
    note: '',
    coordinates: {
      coordinateSpace: 'viewport',
      pageWidth: viewport.width,
      pageHeight: viewport.height,
      rotation: viewport.rotation,
      scale: viewport.scale,
      quads,
      anchorQuads: quads.map(quad => ({ ...quad })),
      notePosition,
      anchorKind: 'SELECTION',
      anchorText: String(selection.text || '').slice(0, 500),
    },
  }
}
