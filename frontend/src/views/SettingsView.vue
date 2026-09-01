<template>
  <div class="settings-page">
    <div class="settings-card api-settings-card">
      <h2>API 设置</h2>
      <div class="api-role-switch" :class="{ 'is-document': activeApiRole === 'DOCUMENT' }">
        <span aria-hidden="true" />
        <button type="button" @click="activeApiRole = 'CHAT'">对话 API</button>
        <button type="button" @click="activeApiRole = 'DOCUMENT'">解析 API</button>
      </div>
      <p class="settings-desc">{{ activeApiRole === 'CHAT'
        ? '用于日常对话和 Agent 工具调用。接口需要支持连续 Tool Calling。'
        : '用于图片、PDF 和 Word 附件理解。图片能力必须通过，原生 PDF 自动检测。' }}</p>
      <el-form label-position="top" class="settings-form">
        <el-form-item label="接口地址（URL）"><el-input v-model="activeBaseUrl" size="large" /></el-form-item>
        <el-form-item label="模型" class="model-catalog-form-item">
          <div class="model-query-row">
            <el-input v-model="activeModel" size="large" placeholder="输入模型名称" />
            <el-button :loading="queryingModels" @click="queryAvailableModels">查询可用模型</el-button>
          </div>
          <section v-if="modelCatalogVisible" class="model-catalog-panel" aria-label="可用模型">
            <div v-if="activeAvailableModels.length" class="model-catalog-list">
              <button
                v-for="item in activeAvailableModels"
                :key="item"
                type="button"
                :class="{ 'is-selected': item === activeModel }"
                @click="activeModel = item"
              >
                {{ item }}
              </button>
            </div>
            <div v-else class="model-catalog-empty">{{ modelCatalogStatus }}</div>
            <footer class="model-catalog-status" :class="{ 'is-error': modelCatalogError }">
              <span class="model-catalog-status__dot" aria-hidden="true" />
              <span>{{ modelCatalogStatus }}</span>
            </footer>
          </section>
        </el-form-item>
        <el-form-item label="接口密钥（API Key）"><el-input v-model="activeApiKey" type="password" show-password size="large" /></el-form-item>
      </el-form>
      <p class="field-hint">调用协议：{{ activeProtocolHint }}</p>
      <div class="settings-actions">
        <el-button type="primary" @click="activeApiRole === 'CHAT' ? testConnection() : testDocumentConnection()" :loading="testing || testingDocument" size="large">
          {{ activeApiRole === 'CHAT' ? '测试对话能力' : '测试解析能力' }}
        </el-button>
        <el-button @click="saveSettings" :loading="saving" size="large">保存设置</el-button>
      </div>
      <div v-if="activeTestResult" class="test-result" :class="{ success: activeTestResult.success, fail: !activeTestResult.success }">
        <div>{{ activeTestResult.message }}</div>
      </div>
    </div>

    <div class="settings-card">
      <h2>学术来源</h2>
      <p class="settings-desc">
        配置额外的学术搜索来源。OpenAlex 免费可用；IEEE Xplore 与 ACM DL 通常需要机构授权或 API Key。
      </p>

      <el-form label-width="160px" label-position="left" class="settings-form">
        <el-form-item label="OpenAlex">
          <el-switch v-model="openalexEnabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>

        <el-form-item label="IEEE Xplore">
          <el-switch v-model="ieeeXploreEnabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="IEEE API Key">
          <el-input v-model="ieeeXploreApiKey" type="password" show-password placeholder="没有可不填" size="large" />
        </el-form-item>

        <el-form-item label="ACM DL">
          <el-switch v-model="acmDlEnabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="ACM API URL">
          <el-input v-model="acmDlApiUrl" placeholder="如 https://your-acm-proxy.example/search" size="large" />
        </el-form-item>
        <el-form-item label="ACM API Key">
          <el-input v-model="acmDlApiKey" type="password" show-password placeholder="没有可不填" size="large" />
        </el-form-item>
      </el-form>

      <div class="settings-actions">
        <el-button type="primary" @click="saveSettings" :loading="saving" size="large">
          保存设置
        </el-button>
      </div>
    </div>

    <div class="settings-card">
      <h2>PDF 解析器</h2>
      <p class="settings-desc">
        默认使用 PDFBox；如果本地安装了 Marker、MinerU 等版面恢复解析器，
        可切换为外部命令以提升双栏、公式、图表的提取质量。命令执行失败会自动回退 PDFBox。
      </p>

      <el-form label-width="160px" label-position="left" class="settings-form">
        <el-form-item label="解析器">
          <el-select v-model="pdfParserProvider" size="large" style="width: 100%;">
            <el-option label="PDFBox（默认，兼容性好）" value="PDFBOX" />
            <el-option label="外部命令（Marker / MinerU / Grobid）" value="EXTERNAL" />
          </el-select>
        </el-form-item>

        <el-form-item label="外部命令">
          <el-input
            v-model="pdfParserExternalCommand"
            type="textarea"
            :rows="2"
            placeholder="例如 marker_single {input} {output} 或 magic-pdf pdf-command {input}"
            size="large"
          />
        </el-form-item>

        <el-form-item label="版面低置信度回退">
          <el-switch v-model="pdfLayoutFallbackEnabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>

        <el-form-item v-if="pdfLayoutFallbackEnabled" label="回退格式">
          <el-select v-model="pdfLayoutFallbackProvider" size="large" style="width: 100%;">
            <el-option label="自动识别" value="AUTO" />
            <el-option label="GROBID TEI（需坐标）" value="GROBID" />
            <el-option label="MinerU Layout JSON" value="MINERU" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="pdfLayoutFallbackEnabled" label="版面回退命令">
          <el-input
            v-model="pdfLayoutFallbackCommand"
            type="textarea"
            :rows="2"
            placeholder="命令可使用 {input} 与 {output}，例如 ra-mineru-adapter --input {input} --output {output}"
            size="large"
          />
          <p class="field-hint">
            仅当 PDFBox 版面质量低于门槛时执行；输出无页码/坐标或质量没有提升时仍保留 PDFBox 结果。
          </p>
        </el-form-item>

        <el-form-item label="PDF 阅读器">
          <el-switch v-model="pdfJsViewerEnabled" active-text="PDF.js（支持批注）" inactive-text="浏览器原生 iframe" />
        </el-form-item>
      </el-form>

      <div class="settings-actions">
        <el-button type="primary" @click="saveSettings" :loading="saving" size="large">
          保存设置
        </el-button>
      </div>
    </div>

    <div class="settings-card">
      <h2>公式与图表</h2>
      <p class="settings-desc">
        启用外部 LaTeX-OCR 与图表提取命令后，论文分析结果会自动展示识别出的公式与图表区域。
        需要先本地安装 pix2tex 等工具；未配置时不会执行。
      </p>

      <el-form label-width="160px" label-position="left" class="settings-form">
        <el-form-item label="LaTeX-OCR">
          <el-switch v-model="formulaExtractorEnabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="公式提取命令">
          <el-input
            v-model="formulaExtractorCommand"
            type="textarea"
            :rows="2"
            placeholder="例如 python -m pix2tex --pdf"
            size="large"
          />
        </el-form-item>

        <el-form-item label="图表提取">
          <el-switch v-model="figureExtractorEnabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="图表提取命令">
          <el-input
            v-model="figureExtractorCommand"
            type="textarea"
            :rows="2"
            placeholder="例如 python -m figure_extractor --pdf"
            size="large"
          />
        </el-form-item>
      </el-form>

      <div class="settings-actions">
        <el-button type="primary" @click="saveSettings" :loading="saving" size="large">
          保存设置
        </el-button>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import api from '@/api'
import { ElMessage } from 'element-plus'
const activeApiRole = ref('CHAT')
const apiKey = ref('')
const savedApiKey = ref('')
const model = ref('')
const baseUrl = ref('')
const testing = ref(false)
const saving = ref(false)
const testResult = ref(null)
const documentBaseUrl = ref('')
const documentModel = ref('')
const documentApiKey = ref('')
const savedDocumentApiKey = ref('')
const testingDocument = ref(false)
const documentTestResult = ref(null)
const queryingModels = ref(false)
const modelCatalogVisible = ref(false)
const modelCatalogError = ref(false)
const modelCatalogStatus = ref('')
const chatAvailableModels = ref([])
const documentAvailableModels = ref([])
const activeBaseUrl = computed({
  get: () => activeApiRole.value === 'CHAT' ? baseUrl.value : documentBaseUrl.value,
  set: value => { if (activeApiRole.value === 'CHAT') baseUrl.value = value; else documentBaseUrl.value = value },
})
const activeApiKey = computed({
  get: () => activeApiRole.value === 'CHAT' ? apiKey.value : documentApiKey.value,
  set: value => { if (activeApiRole.value === 'CHAT') apiKey.value = value; else documentApiKey.value = value },
})
const activeModel = computed({
  get: () => activeApiRole.value === 'CHAT' ? model.value : documentModel.value,
  set: value => { if (activeApiRole.value === 'CHAT') model.value = value; else documentModel.value = value },
})
const activeAvailableModels = computed(() => activeApiRole.value === 'CHAT'
  ? chatAvailableModels.value : documentAvailableModels.value)
const activeTestResult = computed(() => activeApiRole.value === 'CHAT' ? testResult.value : documentTestResult.value)
const activeProtocolHint = computed(() => {
  if (activeApiRole.value === 'CHAT') return 'OpenAI Compatible（自动）'
  const url = documentBaseUrl.value.toLowerCase()
  return url.includes('generativelanguage.googleapis.com') || /\/v1(?:beta|alpha)(?:\/|$)/.test(url)
    ? 'Gemini Native（根据 URL 自动识别）'
    : 'OpenAI Compatible（根据 URL 自动识别）'
})

watch(activeApiRole, () => {
  modelCatalogVisible.value = false
  modelCatalogError.value = false
  modelCatalogStatus.value = ''
})

const openalexEnabled = ref(false)
const ieeeXploreEnabled = ref(false)
const ieeeXploreApiKey = ref('')
const savedIeeeXploreApiKey = ref('')
const acmDlEnabled = ref(false)
const acmDlApiUrl = ref('')
const acmDlApiKey = ref('')
const savedAcmDlApiKey = ref('')

const pdfParserProvider = ref('PDFBOX')
const pdfParserExternalCommand = ref('')
const pdfLayoutFallbackEnabled = ref(false)
const pdfLayoutFallbackProvider = ref('AUTO')
const pdfLayoutFallbackCommand = ref('')
const pdfJsViewerEnabled = ref(true)

const formulaExtractorEnabled = ref(false)
const formulaExtractorCommand = ref('')
const figureExtractorEnabled = ref(false)
const figureExtractorCommand = ref('')

async function loadSettings() {
  try {
    const res = await api.get('/settings')
    const list = res.data
    for (const item of list) {
      if (item.keyName === 'api_key') {
        savedApiKey.value = item.value || ''
        // 后端返回的是脱敏后的 Key，直接显示在密码框中，提示用户已保存
        apiKey.value = item.value || ''
      }
      if (item.keyName === 'model') model.value = item.value || ''
      if (item.keyName === 'base_url') baseUrl.value = item.value || ''
      if (item.keyName === 'document_base_url') documentBaseUrl.value = item.value || ''
      if (item.keyName === 'document_model') documentModel.value = item.value || ''
      if (item.keyName === 'document_api_key') {
        documentApiKey.value = item.value || ''
        savedDocumentApiKey.value = item.value || ''
      }
      if (item.keyName === 'openalex_enabled') openalexEnabled.value = item.value === 'true'
      if (item.keyName === 'ieee_xplore_enabled') ieeeXploreEnabled.value = item.value === 'true'
      if (item.keyName === 'ieee_xplore_api_key') {
        ieeeXploreApiKey.value = item.value || ''
        savedIeeeXploreApiKey.value = item.value || ''
      }
      if (item.keyName === 'acm_dl_enabled') acmDlEnabled.value = item.value === 'true'
      if (item.keyName === 'acm_dl_api_url') acmDlApiUrl.value = item.value || ''
      if (item.keyName === 'acm_dl_api_key') {
        acmDlApiKey.value = item.value || ''
        savedAcmDlApiKey.value = item.value || ''
      }
      if (item.keyName === 'pdf_parser_provider') pdfParserProvider.value = item.value || 'PDFBOX'
      if (item.keyName === 'pdf_parser_external_command') pdfParserExternalCommand.value = item.value || ''
      if (item.keyName === 'pdf_layout_fallback_enabled') pdfLayoutFallbackEnabled.value = item.value === 'true'
      if (item.keyName === 'pdf_layout_fallback_provider') pdfLayoutFallbackProvider.value = item.value || 'AUTO'
      if (item.keyName === 'pdf_layout_fallback_command') pdfLayoutFallbackCommand.value = item.value || ''
      if (item.keyName === 'pdf_js_viewer_enabled') pdfJsViewerEnabled.value = item.value === 'true'
      if (item.keyName === 'formula_extractor_enabled') formulaExtractorEnabled.value = item.value === 'true'
      if (item.keyName === 'formula_extractor_command') formulaExtractorCommand.value = item.value || ''
      if (item.keyName === 'figure_extractor_enabled') figureExtractorEnabled.value = item.value === 'true'
      if (item.keyName === 'figure_extractor_command') figureExtractorCommand.value = item.value || ''
    }
  } catch (e) { /* 首次使用 */ }
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    const res = await api.post('/settings/capabilities/CHAT/test', capabilityTestPayload('CHAT'))
    testResult.value = { success: res.data?.status === 'VERIFIED', message: res.data?.status === 'VERIFIED' ? 'Agent 连续工具调用已验证' : (res.data?.errorMessage || '能力测试失败') }
  } catch (e) {
    testResult.value = {
      success: false,
      message: e.response?.data?.message || '连接测试失败',
    }
  } finally {
    testing.value = false
  }
}

async function testDocumentConnection() {
  testingDocument.value = true
  documentTestResult.value = null
  try {
    const res = await api.post('/settings/capabilities/DOCUMENT/test', capabilityTestPayload('DOCUMENT'))
    documentTestResult.value = { ...res.data, success: res.data?.status === 'VERIFIED', message: res.data?.status === 'VERIFIED' ? `图片输入已验证；原生 PDF ${res.data?.pdf ? '已启用' : '不支持，将使用结构化文本'}` : (res.data?.errorMessage || '连接测试失败') }
  } catch (e) {
    documentTestResult.value = { success: false, image: false, pdf: false, message: e.response?.data?.message || '连接测试失败' }
  } finally { testingDocument.value = false }
}

function capabilityTestPayload(role) {
  const enteredKey = (role === 'CHAT' ? apiKey.value : documentApiKey.value).trim()
  const savedKey = role === 'CHAT' ? savedApiKey.value : savedDocumentApiKey.value
  return {
    baseUrl: (role === 'CHAT' ? baseUrl.value : documentBaseUrl.value).trim(),
    model: (role === 'CHAT' ? model.value : documentModel.value).trim(),
    // A masked value means “use the persisted secret”; a newly entered value is
    // sent only for this probe and is never written by the test endpoint.
    apiKey: enteredKey && enteredKey !== savedKey ? enteredKey : '',
  }
}

async function saveSettings() {
  saving.value = true
  try {
    await doSave()
    // 保存后重新加载，使 API Key 显示为后端返回的脱敏值
    await loadSettings()
    ElMessage({ message: '设置已保存', type: 'success' })
  } catch (e) {
    ElMessage({ message: '保存失败', type: 'error' })
  } finally {
    saving.value = false
  }
}

async function doSave() {
  const payload = []
  const keyValue = apiKey.value.trim()
  const changedApiKey = Boolean(keyValue && keyValue !== savedApiKey.value)
  const documentKeyValue = documentApiKey.value.trim()
  const changedDocumentKey = Boolean(documentKeyValue && documentKeyValue !== savedDocumentApiKey.value)
  const changedIeeeKey = Boolean(ieeeXploreApiKey.value.trim() && ieeeXploreApiKey.value !== savedIeeeXploreApiKey.value)
  const changedAcmKey = Boolean(acmDlApiKey.value.trim() && acmDlApiKey.value !== savedAcmDlApiKey.value)
  // 只有在用户真正修改了 API Key（与加载回来的脱敏值不同）时才提交
  if (changedApiKey) {
    payload.push({ keyName: 'api_key', value: keyValue })
  }
  payload.push({ keyName: 'model', value: model.value.trim() })
  payload.push({ keyName: 'base_url', value: baseUrl.value.trim() })
  payload.push({ keyName: 'document_base_url', value: documentBaseUrl.value.trim() })
  payload.push({ keyName: 'document_model', value: documentModel.value.trim() })
  if (changedDocumentKey) payload.push({ keyName: 'document_api_key', value: documentKeyValue })
  payload.push({ keyName: 'openalex_enabled', value: String(openalexEnabled.value) })
  payload.push({ keyName: 'ieee_xplore_enabled', value: String(ieeeXploreEnabled.value) })
  if (changedIeeeKey) {
    payload.push({ keyName: 'ieee_xplore_api_key', value: ieeeXploreApiKey.value.trim() })
  }
  payload.push({ keyName: 'acm_dl_enabled', value: String(acmDlEnabled.value) })
  if (acmDlApiUrl.value.trim()) {
    payload.push({ keyName: 'acm_dl_api_url', value: acmDlApiUrl.value.trim() })
  }
  if (changedAcmKey) {
    payload.push({ keyName: 'acm_dl_api_key', value: acmDlApiKey.value.trim() })
  }
  payload.push({ keyName: 'pdf_parser_provider', value: pdfParserProvider.value })
  if (pdfParserExternalCommand.value.trim()) {
    payload.push({ keyName: 'pdf_parser_external_command', value: pdfParserExternalCommand.value.trim() })
  }
  payload.push({ keyName: 'pdf_layout_fallback_enabled', value: String(pdfLayoutFallbackEnabled.value) })
  payload.push({ keyName: 'pdf_layout_fallback_provider', value: pdfLayoutFallbackProvider.value })
  if (pdfLayoutFallbackCommand.value.trim()) {
    payload.push({ keyName: 'pdf_layout_fallback_command', value: pdfLayoutFallbackCommand.value.trim() })
  }
  payload.push({ keyName: 'pdf_js_viewer_enabled', value: String(pdfJsViewerEnabled.value) })
  payload.push({ keyName: 'formula_extractor_enabled', value: String(formulaExtractorEnabled.value) })
  if (formulaExtractorCommand.value.trim()) {
    payload.push({ keyName: 'formula_extractor_command', value: formulaExtractorCommand.value.trim() })
  }
  payload.push({ keyName: 'figure_extractor_enabled', value: String(figureExtractorEnabled.value) })
  if (figureExtractorCommand.value.trim()) {
    payload.push({ keyName: 'figure_extractor_command', value: figureExtractorCommand.value.trim() })
  }
  if (payload.length) {
    await api.put('/settings', payload)
    if (changedApiKey) savedApiKey.value = keyValue
    if (changedDocumentKey) savedDocumentApiKey.value = documentKeyValue
    if (changedIeeeKey) savedIeeeXploreApiKey.value = ieeeXploreApiKey.value.trim()
    if (changedAcmKey) savedAcmDlApiKey.value = acmDlApiKey.value.trim()
  }
}

async function queryAvailableModels() {
  if (!activeBaseUrl.value.trim() || !activeApiKey.value.trim()) {
    ElMessage.warning('请先填写 URL 和 API Key')
    return
  }
  queryingModels.value = true
  modelCatalogVisible.value = true
  modelCatalogError.value = false
  modelCatalogStatus.value = '正在查询可用模型…'
  if (activeApiRole.value === 'CHAT') chatAvailableModels.value = []
  else documentAvailableModels.value = []
  try {
    const res = await api.post('/settings/models', {
      baseUrl: activeBaseUrl.value.trim(),
      apiKey: activeApiKey.value.trim(),
      role: activeApiRole.value,
    })
    const values = Array.isArray(res.data?.models) ? res.data.models : []
    if (activeApiRole.value === 'CHAT') chatAvailableModels.value = values
    else documentAvailableModels.value = values
    modelCatalogStatus.value = `已获取 ${values.length} 个可用模型`
  } catch (error) {
    modelCatalogError.value = true
    modelCatalogStatus.value = error.response?.data?.message || error.message || '查询可用模型失败'
  } finally {
    queryingModels.value = false
  }
}

function capabilityLabel(key) {
  return {
    chat: '文本对话',
    stream: '流式输出',
    structured: '结构化输出',
    vision: '图片输入',
    toolCalling: '工具调用',
    continuousTools: '连续工具调用',
  }[key] || key
}

onMounted(() => {
  loadSettings()
})
</script>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  padding: 60px 20px;
  min-height: 100vh;
  background: var(--ra-bg);
}
.settings-card {
  background: var(--ra-panel-bg);
  border-radius: 8px;
  padding: 32px 40px;
  max-width: 560px;
  width: 100%;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.settings-card h2 { margin: 0 0 8px; font-size: 20px; }
.api-role-switch {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  height: 48px;
  margin: 22px 0 18px;
  overflow: hidden;
  border: 1px solid var(--ra-border);
  border-radius: 12px;
  background: var(--ra-bg);
}
.api-role-switch > span {
  position: absolute;
  inset: 3px 50% 3px 3px;
  border: 1px solid var(--ra-link);
  border-radius: 9px;
  background: var(--ra-active-bg);
  transition: transform .2s ease;
}
.api-role-switch.is-document > span { transform: translateX(calc(100% + 3px)); }
.api-role-switch button {
  position: relative;
  z-index: 1;
  border: 0;
  background: transparent;
  color: var(--ra-text-secondary);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.model-catalog-form-item .el-form-item__content { display: block; }
.model-query-row { display: flex; width: 100%; gap: 10px; }
.model-query-row .el-input { min-width: 0; flex: 1; }
.model-catalog-panel {
  width: 100%;
  margin-top: 8px;
  overflow: hidden;
  box-sizing: border-box;
  border: 1px solid var(--ra-border);
  border-radius: 8px;
  background: var(--ra-panel-bg);
}
.model-catalog-list {
  display: flex;
  max-height: 136px;
  overflow-y: auto;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 8px;
  padding: 12px;
  scrollbar-gutter: stable;
}
.model-catalog-list button {
  display: inline-flex;
  max-width: 100%;
  padding: 7px 10px;
  overflow: hidden;
  align-items: center;
  border: 1px solid var(--ra-border);
  border-radius: 7px;
  background: var(--ra-card-bg);
  color: var(--ra-text-secondary);
  font: inherit;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.model-catalog-list button:hover { background: var(--ra-hover-bg); color: var(--ra-text); }
.model-catalog-list button.is-selected { border-color: var(--ra-link); background: var(--ra-active-bg); color: var(--ra-active-text); }
.model-catalog-empty { padding: 18px 10px; color: var(--ra-text-tertiary); font-size: 12px; text-align: center; }
.model-catalog-status {
  display: flex;
  min-height: 30px;
  padding: 0 10px;
  align-items: center;
  gap: 7px;
  border-top: 1px solid var(--ra-border-light);
  color: var(--ra-text-tertiary);
  font-size: 11px;
}
.model-catalog-status__dot { width: 6px; height: 6px; border-radius: 50%; background: #67c23a; }
.model-catalog-status.is-error { color: #f56c6c; }
.model-catalog-status.is-error .model-catalog-status__dot { background: #f56c6c; }
.settings-desc { font-size: 13px; color: var(--ra-text-tertiary); margin: 0 0 24px; line-height: 1.6; }
.settings-form { margin-bottom: 20px; }
.settings-actions { display: flex; gap: 12px; margin-bottom: 16px; }
.field-hint {
  font-size: 12px;
  color: var(--ra-text-tertiary);
  margin: 6px 0 0;
  line-height: 1.5;
}
.test-result { padding: 10px 16px; border-radius: 6px; font-size: 14px; }
.test-result.success { background: #f0f9eb; color: #67c23a; }
.test-result.fail { background: #fef0f0; color: #f56c6c; }
.capability-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 8px;
  font-size: 12px;
}
html.dark .test-result.success { background: #1e3924; color: #85ce61; }
html.dark .test-result.fail { background: #3b1e1e; color: #f89898; }
</style>
