export const EVIDENCE_STATE_META = Object.freeze({
  NEEDS_EVIDENCE: { label: '待补证据', type: 'info' },
  SUPPORTED: { label: '已有支撑', type: 'success' },
  CONFLICTING: { label: '存在反证', type: 'danger' },
  MIXED: { label: '证据不一致', type: 'warning' },
})

export const EVIDENCE_RELATION_META = Object.freeze({
  SUPPORTS: { label: '支持', type: 'success' },
  CONTRADICTS: { label: '反驳', type: 'danger' },
  CONTEXT: { label: '背景', type: 'info' },
})

export function evidenceStateMeta(state) {
  return EVIDENCE_STATE_META[state] || EVIDENCE_STATE_META.NEEDS_EVIDENCE
}

export function evidenceRelationMeta(relation) {
  return EVIDENCE_RELATION_META[relation] || EVIDENCE_RELATION_META.CONTEXT
}

export function summarizeClaims(claims = []) {
  const summary = {
    total: claims.length,
    supported: 0,
    needsEvidence: 0,
    risk: 0,
    coverage: 0,
  }
  claims.forEach((claim) => {
    if (claim.evidenceState === 'SUPPORTED') summary.supported += 1
    else if (claim.evidenceState === 'NEEDS_EVIDENCE') summary.needsEvidence += 1
    else summary.risk += 1
  })
  summary.coverage = summary.total
    ? Math.round(((summary.total - summary.needsEvidence) / summary.total) * 100)
    : 0
  return summary
}

export function buildEvidenceResearchLocation(evidence, projectId) {
  const query = {
    page: String(Math.max(1, Number(evidence?.pageNumber) || 1)),
    mode: 'selection',
    returnTo: `/writing?project=${projectId}`,
  }
  if (evidence?.researchSessionId) query.session = String(evidence.researchSessionId)
  return {
    path: `/research/${evidence.paperId}`,
    query,
  }
}
