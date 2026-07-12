<template>
  <div class="settings-page">
    <div class="settings-card">
      <h2>API 设置</h2>
      <p class="settings-desc">
        配置大语言模型 API。支持任意兼容 OpenAI 接口的服务（DeepSeek、OpenAI、Ollama、vLLM 等）。
        请自行前往对应平台注册获取 API Key。
      </p>

      <el-form label-width="100px" label-position="left" class="settings-form">
        <el-form-item label="API Key">
          <el-input v-model="apiKey" type="password" show-password placeholder="例如 sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" size="large" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="model" placeholder="例如 deepseek-chat / kimi-k2.6 / gpt-4o…" size="large" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="baseUrl" placeholder="例如 https://api.moonshot.ai/v1" size="large" />
        </el-form-item>
        <el-form-item label="研究主题">
          <el-input v-model="researchTopic" type="textarea" :rows="2" placeholder="例如：多模态大模型在医疗影像中的应用" size="large" />
          <p class="field-hint">
            用于分析论文与本研究方向的匹配度，影响入库时的相关性评分与推荐理由。
          </p>
        </el-form-item>
      </el-form>

      <div class="settings-actions">
        <el-button type="primary" @click="testConnection" :loading="testing" size="large">
          测试连接
        </el-button>
        <el-button @click="saveSettings" :loading="saving" size="large">
          保存设置
        </el-button>
      </div>

      <div v-if="testResult !== null" class="test-result" :class="{ success: testResult, fail: !testResult }">
        {{ testResult ? '✅ 连接成功' : '❌ 连接失败 — 请检查 API Key、模型名和 Base URL' }}
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

    <div class="settings-card">
      <h2>导出与同步</h2>
      <p class="settings-desc">
        配置 Obsidian vault 路径可将论文导出为 Markdown + BibTeX；配置 Zotero 信息可通过 Web API 推送条目。
      </p>

      <el-form label-width="160px" label-position="left" class="settings-form">
        <el-form-item label="Obsidian Vault">
          <el-input v-model="obsidianVaultPath" placeholder="如 C:\\Users\\xxx\\Documents\\Obsidian Vault" size="large" />
        </el-form-item>

        <el-form-item label="Zotero User ID">
          <el-input v-model="zoteroUserId" placeholder="Zotero user ID" size="large" />
        </el-form-item>
        <el-form-item label="Zotero API Key">
          <el-input v-model="zoteroApiKey" type="password" show-password placeholder="Zotero API key" size="large" />
        </el-form-item>
        <el-form-item label="Zotero Collection">
          <el-input v-model="zoteroCollectionKey" placeholder="目标 collection key（可选）" size="large" />
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
import { ref, onMounted } from 'vue'
import api from '@/api'
import { ElMessage } from 'element-plus'

const apiKey = ref('')
const savedApiKey = ref('')
const model = ref('')
const baseUrl = ref('')
const researchTopic = ref('')
const testing = ref(false)
const saving = ref(false)
const testResult = ref(null)

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
const pdfJsViewerEnabled = ref(true)

const formulaExtractorEnabled = ref(false)
const formulaExtractorCommand = ref('')
const figureExtractorEnabled = ref(false)
const figureExtractorCommand = ref('')

const obsidianVaultPath = ref('')
const zoteroUserId = ref('')
const zoteroApiKey = ref('')
const savedZoteroApiKey = ref('')
const zoteroCollectionKey = ref('')

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
      if (item.keyName === 'research_topic') researchTopic.value = item.value || ''
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
      if (item.keyName === 'pdf_js_viewer_enabled') pdfJsViewerEnabled.value = item.value === 'true'
      if (item.keyName === 'formula_extractor_enabled') formulaExtractorEnabled.value = item.value === 'true'
      if (item.keyName === 'formula_extractor_command') formulaExtractorCommand.value = item.value || ''
      if (item.keyName === 'figure_extractor_enabled') figureExtractorEnabled.value = item.value === 'true'
      if (item.keyName === 'figure_extractor_command') figureExtractorCommand.value = item.value || ''
      if (item.keyName === 'obsidian_vault_path') obsidianVaultPath.value = item.value || ''
      if (item.keyName === 'zotero_user_id') zoteroUserId.value = item.value || ''
      if (item.keyName === 'zotero_api_key') {
        zoteroApiKey.value = item.value || ''
        savedZoteroApiKey.value = item.value || ''
      }
      if (item.keyName === 'zotero_collection_key') zoteroCollectionKey.value = item.value || ''
    }
  } catch (e) { /* 首次使用 */ }
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    await doSave()
    const res = await api.post('/settings/test')
    testResult.value = res.data.success
  } catch (e) {
    testResult.value = false
  } finally {
    testing.value = false
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
  const changedIeeeKey = Boolean(ieeeXploreApiKey.value.trim() && ieeeXploreApiKey.value !== savedIeeeXploreApiKey.value)
  const changedAcmKey = Boolean(acmDlApiKey.value.trim() && acmDlApiKey.value !== savedAcmDlApiKey.value)
  const changedZoteroKey = Boolean(zoteroApiKey.value.trim() && zoteroApiKey.value !== savedZoteroApiKey.value)
  // 只有在用户真正修改了 API Key（与加载回来的脱敏值不同）时才提交
  if (changedApiKey) {
    payload.push({ keyName: 'api_key', value: keyValue })
  }
  payload.push({ keyName: 'model', value: model.value })
  payload.push({ keyName: 'base_url', value: baseUrl.value })
  payload.push({ keyName: 'research_topic', value: researchTopic.value })
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
  payload.push({ keyName: 'pdf_js_viewer_enabled', value: String(pdfJsViewerEnabled.value) })
  payload.push({ keyName: 'formula_extractor_enabled', value: String(formulaExtractorEnabled.value) })
  if (formulaExtractorCommand.value.trim()) {
    payload.push({ keyName: 'formula_extractor_command', value: formulaExtractorCommand.value.trim() })
  }
  payload.push({ keyName: 'figure_extractor_enabled', value: String(figureExtractorEnabled.value) })
  if (figureExtractorCommand.value.trim()) {
    payload.push({ keyName: 'figure_extractor_command', value: figureExtractorCommand.value.trim() })
  }
  if (obsidianVaultPath.value.trim()) {
    payload.push({ keyName: 'obsidian_vault_path', value: obsidianVaultPath.value.trim() })
  }
  if (zoteroUserId.value.trim()) {
    payload.push({ keyName: 'zotero_user_id', value: zoteroUserId.value.trim() })
  }
  if (changedZoteroKey) {
    payload.push({ keyName: 'zotero_api_key', value: zoteroApiKey.value.trim() })
  }
  if (zoteroCollectionKey.value.trim()) {
    payload.push({ keyName: 'zotero_collection_key', value: zoteroCollectionKey.value.trim() })
  }
  if (payload.length) {
    await api.put('/settings', payload)
    if (changedApiKey) savedApiKey.value = keyValue
    if (changedIeeeKey) savedIeeeXploreApiKey.value = ieeeXploreApiKey.value.trim()
    if (changedAcmKey) savedAcmDlApiKey.value = acmDlApiKey.value.trim()
    if (changedZoteroKey) savedZoteroApiKey.value = zoteroApiKey.value.trim()
  }
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
  min-height: calc(100vh - 61px);
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
html.dark .test-result.success { background: #1e3924; color: #85ce61; }
html.dark .test-result.fail { background: #3b1e1e; color: #f89898; }
</style>
