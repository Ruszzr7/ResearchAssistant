<template>
  <div class="settings-page">
    <div class="settings-card api-settings-card">
      <header class="settings-header">
        <h2>API 设置</h2>
        <button class="settings-close" type="button" aria-label="关闭 API 设置" @click="closeSettings">×</button>
      </header>

      <div class="settings-body">
        <p class="settings-desc">
          同一模型用于 Agent 对话、工具调用和论文理解。必须支持文字、图片及连续工具调用；原生 PDF 为可选能力。
        </p>
        <el-form label-position="top" class="settings-form">
          <el-form-item label="接口地址（URL）"><el-input v-model="baseUrl" size="large" /></el-form-item>
          <el-form-item label="模型" class="model-catalog-form-item">
            <div class="model-query-row">
              <el-input v-model="model" size="large" placeholder="输入模型名称" />
              <el-button :loading="queryingModels" @click="queryAvailableModels">查询可用模型</el-button>
            </div>
            <section v-if="modelCatalogVisible" class="model-catalog-panel" aria-label="可用模型">
              <div v-if="availableModels.length" class="model-catalog-list">
                <button
                  v-for="item in availableModels"
                  :key="item"
                  type="button"
                  :class="{ 'is-selected': item === model }"
                  @click="model = item"
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
          <el-form-item label="接口密钥（API Key）"><el-input v-model="apiKey" type="password" show-password size="large" /></el-form-item>
        </el-form>
        <p class="field-hint">调用协议：{{ protocolHint }}</p>
        <div v-if="testResult" class="test-result" :class="{ success: testResult.success, fail: !testResult.success }">
          <div>{{ testResult.message }}</div>
          <div v-if="testResult.success" class="capability-list">
            <span>文字 ✓</span><span>图片 ✓</span><span>工具调用 ✓</span>
            <span>工具后图片 ✓</span><span>结构化输出 ✓</span>
            <span>原生 PDF {{ testResult.pdf ? '✓' : '—' }}</span>
          </div>
        </div>
      </div>

      <footer class="settings-actions">
        <el-button @click="testConnection" :loading="testing" size="large">测试连接</el-button>
        <el-button type="primary" @click="saveSettings" :loading="saving" size="large">保存设置</el-button>
      </footer>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { ElMessage } from 'element-plus'
const router = useRouter()
const apiKey = ref('')
const savedApiKey = ref('')
const model = ref('')
const baseUrl = ref('')
const testing = ref(false)
const saving = ref(false)
const testResult = ref(null)
const queryingModels = ref(false)
const modelCatalogVisible = ref(false)
const modelCatalogError = ref(false)
const modelCatalogStatus = ref('')
const availableModels = ref([])
const protocolHint = computed(() => {
  const url = baseUrl.value.toLowerCase()
  return url.includes('generativelanguage.googleapis.com') || /\/v1(?:beta|alpha)(?:\/|$)/.test(url)
    ? 'Gemini Native（根据 URL 自动识别）'
    : 'OpenAI Compatible（根据 URL 自动识别）'
})

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
    }
  } catch (e) { /* 首次使用 */ }
}

function closeSettings() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  router.push('/')
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    const res = await api.post('/settings/capabilities/test', capabilityTestPayload())
    const verified = res.data?.status === 'VERIFIED'
    testResult.value = {
      ...res.data,
      success: verified,
      message: verified
        ? `统一多模态能力已验证；原生 PDF ${res.data?.pdf ? '可用' : '不支持，将使用结构化文本与局部图片'}`
        : (res.data?.message || '能力测试失败'),
    }
  } catch (e) {
    testResult.value = {
      success: false,
      message: e.response?.data?.message || '连接测试失败',
    }
  } finally {
    testing.value = false
  }
}

function capabilityTestPayload() {
  const enteredKey = apiKey.value.trim()
  return {
    baseUrl: baseUrl.value.trim(),
    model: model.value.trim(),
    // A masked value means “use the persisted secret”; a newly entered value is
    // sent only for this probe and is never written by the test endpoint.
    apiKey: enteredKey && enteredKey !== savedApiKey.value ? enteredKey : '',
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
  // 只有在用户真正修改了 API Key（与加载回来的脱敏值不同）时才提交
  if (changedApiKey) {
    payload.push({ keyName: 'api_key', value: keyValue })
  }
  payload.push({ keyName: 'model', value: model.value.trim() })
  payload.push({ keyName: 'base_url', value: baseUrl.value.trim() })
  if (payload.length) {
    await api.put('/settings', payload)
    if (changedApiKey) savedApiKey.value = keyValue
  }
}

async function queryAvailableModels() {
  if (!baseUrl.value.trim() || !apiKey.value.trim()) {
    ElMessage.warning('请先填写 URL 和 API Key')
    return
  }
  queryingModels.value = true
  modelCatalogVisible.value = true
  modelCatalogError.value = false
  modelCatalogStatus.value = '正在查询可用模型…'
  availableModels.value = []
  try {
    const res = await api.post('/settings/models', {
      baseUrl: baseUrl.value.trim(),
      apiKey: apiKey.value.trim(),
    })
    const values = Array.isArray(res.data?.models) ? res.data.models : []
    availableModels.value = values
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
  align-items: center;
  justify-content: center;
  gap: 24px;
  padding: 32px 20px;
  box-sizing: border-box;
  min-height: 100vh;
  background: var(--ra-bg);
}
.settings-card {
  background: var(--ra-panel-bg);
  overflow: hidden;
  border: 1px solid var(--ra-border);
  border-radius: 22px;
  max-width: 650px;
  width: 100%;
  box-shadow: 0 18px 48px rgba(0,0,0,0.18);
}
.settings-header {
  display: flex;
  min-height: 84px;
  padding: 0 26px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--ra-border-light);
}
.settings-header h2 { margin: 0; font-size: 21px; }
.settings-close {
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--ra-text-tertiary);
  font-size: 31px;
  font-weight: 300;
  line-height: 32px;
  cursor: pointer;
}
.settings-close:hover { background: var(--ra-hover-bg); color: var(--ra-text); }
.settings-close:focus-visible { outline: 2px solid var(--ra-link); outline-offset: 2px; }
.settings-body { padding: 26px 26px 22px; }
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
.settings-actions {
  display: flex;
  min-height: 96px;
  padding: 0 26px;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  border-top: 1px solid var(--ra-border-light);
}
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
@media (max-width: 640px) {
  .settings-page { padding: 16px; align-items: flex-start; }
  .settings-card { border-radius: 18px; }
  .settings-header, .settings-actions { padding-right: 20px; padding-left: 20px; }
  .settings-body { padding: 22px 20px 18px; }
  .model-query-row { flex-direction: column; }
  .model-query-row .el-button { width: 100%; margin-left: 0; }
}
html.dark .test-result.success { background: #1e3924; color: #85ce61; }
html.dark .test-result.fail { background: #3b1e1e; color: #f89898; }
</style>
