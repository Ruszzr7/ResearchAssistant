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
const testing = ref(false)
const saving = ref(false)
const testResult = ref(null)

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
  // 只有在用户真正修改了 API Key（与加载回来的脱敏值不同）时才提交
  if (keyValue && keyValue !== savedApiKey.value) {
    payload.push({ keyName: 'api_key', value: keyValue })
    savedApiKey.value = keyValue
  }
  payload.push({ keyName: 'model', value: model.value })
  payload.push({ keyName: 'base_url', value: baseUrl.value })
  if (payload.length) {
    await api.put('/settings', payload)
  }
}

onMounted(() => {
  loadSettings()
})
</script>

<style scoped>
.settings-page {
  display: flex;
  justify-content: center;
  padding: 60px 20px;
  min-height: calc(100vh - 61px);
  background: #f5f6f8;
}
.settings-card {
  background: #fff;
  border-radius: 8px;
  padding: 32px 40px;
  max-width: 560px;
  width: 100%;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.settings-card h2 { margin: 0 0 8px; font-size: 20px; }
.settings-desc { font-size: 13px; color: #909399; margin: 0 0 24px; line-height: 1.6; }
.settings-form { margin-bottom: 20px; }
.settings-actions { display: flex; gap: 12px; margin-bottom: 16px; }
.test-result { padding: 10px 16px; border-radius: 6px; font-size: 14px; }
.test-result.success { background: #f0f9eb; color: #67c23a; }
.test-result.fail { background: #fef0f0; color: #f56c6c; }
</style>
