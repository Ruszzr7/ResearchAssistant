import { reactive, ref } from 'vue'
import api from '@/api'
import { waitForTask } from '@/utils/task.js'
import { ElMessage } from 'element-plus'

/**
 * 论文入库流水线推荐管理。
 *
 * 上传/创建论文后，后端会自动启动 paper-import 工作流。
 * 该 composable 负责轮询工作流结果，并把推荐结果按 paperId 缓存，
 * 供 LibraryView 详情面板展示「应用推荐」入口。
 */
export function usePaperImportRecommendations() {
  const recs = reactive(new Map()) // paperId -> { taskId, status, result, loading, error }

  function getRec(paperId) {
    if (!recs.has(paperId)) {
      recs.set(paperId, {
        taskId: null,
        status: null,
        result: null,
        loading: false,
        error: null,
        importError: null
      })
    }
    return recs.get(paperId)
  }

  async function watchImport(paperId, taskId) {
    const rec = getRec(paperId)
    rec.taskId = taskId
    rec.loading = true
    rec.error = null
    rec.importError = null
    try {
      const result = await waitForTask(api.get.bind(api), taskId, null, stage => {
        rec.status = stage
      })
      rec.result = { ...(rec.result || {}), ...(result || {}) }
      rec.status = '已完成'
    } catch (e) {
      // 导入流水线失败不应覆盖详情面板的元数据/标签/文件夹推荐结果。
      rec.importError = e.message || '导入流水线失败'
      rec.status = '失败'
    } finally {
      rec.loading = false
    }
  }

  async function applyTags(paperId, tagNames) {
    if (!tagNames || tagNames.length === 0) return
    // 已有标签 ID 直接应用；同名标签复用，避免推荐应用时重复创建。
    const existingIds = tagNames
      .filter(v => typeof v === 'number' || /^\d+$/.test(v))
      .map(v => Number(v))
    const newNames = tagNames
      .filter(v => !(typeof v === 'number' || /^\d+$/.test(v)))
      .map(v => String(v).trim())
      .filter(Boolean)

    const tagsResponse = await api.get('/tags')
    const existingTags = tagsResponse.data || []
    const tagByName = new Map(existingTags.map(tag => [String(tag.name).trim().toLowerCase(), tag.id]))
    const reusableIds = []
    const namesToCreate = []
    for (const name of newNames) {
      const knownId = tagByName.get(name.toLowerCase())
      if (knownId != null) reusableIds.push(knownId)
      else namesToCreate.push(name)
    }

    const created = await Promise.all(
      namesToCreate.map(name => api.post('/tags', { name }).then(r => r.data.id))
    )
    const tagIds = [...new Set([...existingIds, ...reusableIds, ...created])]
    await api.post(`/tags/papers/${paperId}/tags`, { tagIds })
    ElMessage.success('标签已应用')
  }

  async function applyFolder(paperId, folderId) {
    await api.post('/papers/batch/move', { ids: [paperId], folderId })
    ElMessage.success('文件夹已应用')
  }

  async function applyMetadata(paperId, metadata) {
    const r = await api.get(`/papers/${paperId}`)
    const paper = r.data
    const fields = ['title', 'authors', 'year', 'source', 'doi', 'arxivId', 'sourceUrl', 'abstractText', 'keywords']
    let changed = false
    for (const f of fields) {
      if (metadata[f] != null && metadata[f] !== '') {
        paper[f] = metadata[f]
        changed = true
      }
    }
    if (changed) {
      await api.put(`/papers/${paperId}`, paper)
      ElMessage.success('元数据已应用')
    }
  }

  return {
    recs,
    ensureRec: getRec,
    watchImport,
    applyTags,
    applyFolder,
    applyMetadata
  }
}
