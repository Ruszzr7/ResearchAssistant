/** Stable selection helpers for paginated tables. */
export function normalizeSelection(ids) {
  const values = new Map()
  for (const id of ids || []) {
    if (id !== null && id !== undefined && !values.has(String(id))) values.set(String(id), id)
  }
  return [...values.values()]
}

export function mergePageSelection(selectedIds, rows, checked) {
  const selected = new Map(normalizeSelection(selectedIds).map(id => [String(id), id]))
  for (const row of rows || []) {
    const id = row?.id
    if (id === null || id === undefined) continue
    if (checked) selected.set(String(id), id)
    else selected.delete(String(id))
  }
  return [...selected.values()]
}

export function removeSelectedIds(selectedIds, idsToRemove) {
  const removed = new Set(normalizeSelection(idsToRemove).map(String))
  return normalizeSelection(selectedIds).filter(id => !removed.has(String(id)))
}

export function selectionContains(selectedIds, id) {
  return new Set(normalizeSelection(selectedIds)).has(String(id))
}
