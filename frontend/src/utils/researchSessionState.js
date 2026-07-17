import { positivePageNumber, positivePaperId, RESEARCH_ROUTE_PATH } from '@/router/workbenchRoute.js'

export const LAST_RESEARCH_ROUTE_KEY = 'research-assistant.last-research-route'

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
