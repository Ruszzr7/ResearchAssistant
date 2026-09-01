export const RESEARCH_ROUTE_PATH = '/research'
export const WORKBENCH_ROUTE_PATH = RESEARCH_ROUTE_PATH

export function positivePaperId(value) {
  const raw = Array.isArray(value) ? value[0] : value
  const id = Number(raw)
  return Number.isInteger(id) && id > 0 ? id : null
}

export function positivePageNumber(value) {
  const raw = Array.isArray(value) ? value[0] : value
  const page = Number(raw)
  return Number.isInteger(page) && page > 0 ? page : null
}

/** Retired workbench routes now converge on the same paper conversation. */
export function legacyWorkbenchRedirect(to) {
  const firstLegacyPaperId = String(to?.query?.paperIds || '').split(',')
    .map(Number).find(id => Number.isInteger(id) && id > 0)
  const paperId = positivePaperId(to?.params?.paperId)
    || positivePaperId(to?.query?.paperId)
    || firstLegacyPaperId
    || null
  const query = { ...(to?.query || {}) }
  delete query.paperId
  delete query.paperIds
  delete query.mode
  return {
    path: paperId ? `${RESEARCH_ROUTE_PATH}/${paperId}` : RESEARCH_ROUTE_PATH,
    query,
  }
}

export function researchRouteLocation(paperId, query = {}) {
  const id = positivePaperId(paperId)
  const normalizedQuery = { ...query }
  delete normalizedQuery.paperId
  delete normalizedQuery.paperIds
  delete normalizedQuery.mode
  return {
    path: id ? `${RESEARCH_ROUTE_PATH}/${id}` : RESEARCH_ROUTE_PATH,
    query: normalizedQuery,
  }
}
