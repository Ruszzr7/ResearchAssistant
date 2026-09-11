import { positivePageNumber, positivePaperId, RESEARCH_ROUTE_PATH } from '@/router/workbenchRoute.js'

export const LAST_RESEARCH_ROUTE_KEY = 'research-assistant.last-research-route'
export const PENDING_RESEARCH_EVIDENCE_KEY = 'research-assistant.pending-evidence'
export const RESEARCH_ARCHIVE_RETURN_KEY = 'research-assistant.archive-return'

function resolveStorage(storage) {
  if (storage) return storage
  try { return globalThis.sessionStorage } catch { return null }
}

function normalizedPaperIds(value) {
  const raw = Array.isArray(value) ? value.join(',') : String(value || '')
  return [...new Set(raw.split(',').map(Number)
    .filter(id => Number.isInteger(id) && id > 0))]
}

export function normalizeResearchLocation(location) {
  const paperId = positivePaperId(location?.paperId ?? location?.params?.paperId)
  if (!paperId) return null
  const query = location?.query || {}
  const page = positivePageNumber(location?.page ?? query.page)
  const session = positivePaperId(location?.session ?? query.session)
  const mode = String(location?.mode ?? query.mode ?? '').trim()
  const paperIds = normalizedPaperIds(location?.paperIds ?? query.paperIds)
  return {
    path: `${RESEARCH_ROUTE_PATH}/${paperId}`,
    query: {
      ...(page ? { page: String(page) } : {}),
      ...(session ? { session: String(session) } : {}),
      ...(mode ? { mode } : {}),
      ...(paperIds.length ? { paperIds: paperIds.join(',') } : {}),
    },
  }
}

export function readLastResearchLocation(storage) {
  const target = resolveStorage(storage)
  if (!target) return null
  try {
    return normalizeResearchLocation(JSON.parse(target.getItem(LAST_RESEARCH_ROUTE_KEY) || 'null'))
  } catch {
    return null
  }
}

export function writeLastResearchLocation(location, storage) {
  const normalized = normalizeResearchLocation(location)
  const target = resolveStorage(storage)
  if (!normalized || !target) return normalized
  try {
    const session = positivePaperId(normalized.query.session)
    target.setItem(LAST_RESEARCH_ROUTE_KEY, JSON.stringify({
      paperId: positivePaperId(normalized.path.split('/').pop()),
      page: positivePageNumber(normalized.query.page),
      ...(session ? { session } : {}),
      mode: normalized.query.mode || '',
      paperIds: normalized.query.paperIds || '',
    }))
  } catch { /* session storage can be unavailable */ }
  return normalized
}

/**
 * 暂存从档案页点击的证据目标。跨论文路由切换会重建 PDF 查看器，
 * 因此不能只依赖组件内存；保留完整 locators，避免回到 PDF 后重新用短文本猜框。
 */
export function writePendingResearchEvidence(evidence, storage) {
  const target = normalizePendingEvidence(evidence)
  const store = resolveStorage(storage)
  if (!target || !store) return target
  try { store.setItem(PENDING_RESEARCH_EVIDENCE_KEY, JSON.stringify(target)) } catch { /* storage unavailable */ }
  return target
}

export function consumePendingResearchEvidence(paperId, storage) {
  const store = resolveStorage(storage)
  if (!store) return null
  let value = null
  try {
    value = JSON.parse(store.getItem(PENDING_RESEARCH_EVIDENCE_KEY) || 'null')
  } catch {
    value = null
  }
  const target = normalizePendingEvidence(value)
  if (!target || positivePaperId(target.paperId) !== positivePaperId(paperId)) return null
  try { store.removeItem?.(PENDING_RESEARCH_EVIDENCE_KEY) } catch { /* storage unavailable */ }
  return target
}

/** 保存从档案进入 PDF 前的列表位置，返回档案时只恢复一次。 */
export function writeResearchArchiveReturnState(state, storage) {
  const store = resolveStorage(storage)
  if (!store) return null
  const value = {
    page: positivePageNumber(state?.page) || 1,
    activeTab: state?.activeTab === 'archived' ? 'archived' : 'active',
    keyword: String(state?.keyword || '').trim(),
    selectedPaperId: positivePaperId(state?.selectedPaperId),
  }
  try { store.setItem(RESEARCH_ARCHIVE_RETURN_KEY, JSON.stringify(value)) } catch { /* storage unavailable */ }
  return value
}

export function consumeResearchArchiveReturnState(storage) {
  const store = resolveStorage(storage)
  if (!store) return null
  let value = null
  try {
    value = JSON.parse(store.getItem(RESEARCH_ARCHIVE_RETURN_KEY) || 'null')
    store.removeItem?.(RESEARCH_ARCHIVE_RETURN_KEY)
  } catch {
    value = null
  }
  if (!value || typeof value !== 'object') return null
  return {
    page: positivePageNumber(value.page) || 1,
    activeTab: value.activeTab === 'archived' ? 'archived' : 'active',
    keyword: String(value.keyword || '').trim(),
    selectedPaperId: positivePaperId(value.selectedPaperId),
  }
}

function normalizePendingEvidence(value) {
  const paperId = positivePaperId(value?.paperId)
  const page = positivePageNumber(value?.page ?? value?.pageNumber)
  if (!paperId || !page) return null
  const rawLocators = Array.isArray(value?.locators) && value.locators.length
    ? value.locators
    : value?.locator ? [value.locator] : []
  const locators = rawLocators.map((locator, index) => ({
    ...locator,
    locatorId: locator?.locatorId || `locator-${index + 1}`,
    pageNumber: positivePageNumber(locator?.pageNumber ?? locator?.page) || page,
    page: positivePageNumber(locator?.pageNumber ?? locator?.page) || page,
    targetText: String(locator?.targetText || '').trim(),
    targetBoxes: Array.isArray(locator?.targetBoxes) ? locator.targetBoxes : [],
    contentBoxes: Array.isArray(locator?.contentBoxes) ? locator.contentBoxes : [],
    focusBoxes: Array.isArray(locator?.focusBoxes) ? locator.focusBoxes : [],
    rects: Array.isArray(locator?.rects) ? locator.rects : [],
  }))
  if (!locators.length) return null
  return {
    ...value,
    paperId,
    page,
    locators,
    locator: { ...(locators[0] || {}) },
  }
}
