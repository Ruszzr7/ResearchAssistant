<template>
  <div class="app-container">
    <el-container>
      <el-header class="app-header">
        <span class="app-title" @click="$router.push('/')">Research Assistant</span>
        <el-menu
          :default-active="$route.path"
          mode="horizontal"
          router
          class="app-nav"
        >
          <el-menu-item index="/">文库管理</el-menu-item>
          <el-menu-item index="/search">文献检索</el-menu-item>
          <el-menu-item index="/analysis">论文分析</el-menu-item>
          <el-menu-item index="/gap">研究空白</el-menu-item>
        </el-menu>
        <el-button text class="settings-btn" @click="showSettings = true">
          <svg width="18" height="18" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 10a2 2 0 100-4 2 2 0 000 4z"/><path d="M14.46 6.54l-1.08-.42a5.1 5.1 0 00-.56-1.36l.42-1.08a.5.5 0 00-.12-.62l-.92-.92a.5.5 0 00-.62-.12l-1.08.42a5.1 5.1 0 00-1.36-.56L8.72 1.54a.5.5 0 00-.46-.34h-1.3a.5.5 0 00-.46.34l-.42 1.08a5.1 5.1 0 00-1.36.56l-1.08-.42a.5.5 0 00-.62.12l-.92.92a.5.5 0 00-.12.62l.42 1.08a5.1 5.1 0 00-.56 1.36l-1.08.42a.5.5 0 00-.34.46v1.3a.5.5 0 00.34.46l1.08.42c.1.48.3.94.56 1.36l-.42 1.08a.5.5 0 00.12.62l.92.92a.5.5 0 00.62.12l1.08-.42c.42.26.88.46 1.36.56l.42 1.08a.5.5 0 00.46.34h1.3a.5.5 0 00.46-.34l.42-1.08c.48-.1.94-.3 1.36-.56l1.08.42a.5.5 0 00.62-.12l.92-.92a.5.5 0 00.12-.62l-.42-1.08c.26-.42.46-.88.56-1.36l1.08-.42a.5.5 0 00.34-.46v-1.3a.5.5 0 00-.34-.46z"/></svg>
        </el-button>
      </el-header>
      <div style="height:1px;background:#dcdfe6;flex-shrink:0;position:relative;z-index:10"></div>
      <el-main>
        <router-view />
      </el-main>
    </el-container>

    <!-- ====== 设置弹窗 ====== -->
    <el-dialog v-model="showSettings" title="API 设置" width="480px" :close-on-click-modal="false">
      <p style="font-size:12px;color:#909399;margin:0 0 16px">
        配置大语言模型 API。支持任意兼容 OpenAI 接口的服务（DeepSeek、OpenAI、Ollama、vLLM 等）。
      </p>
      <el-form label-width="80px" label-position="left">
        <el-form-item label="API Key">
          <el-input v-model="apiKey" type="password" show-password placeholder="例如 sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" size="default" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="model" placeholder="例如 deepseek-chat / kimi-k2.6 / gpt-4o" size="default" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="baseUrl" placeholder="例如 https://api.moonshot.ai/v1" size="default" />
        </el-form-item>
      </el-form>
      <div v-if="testResult !== null" class="test-result" :class="{ success: testResult.success, fail: !testResult.success }">
        {{ testResult.success ? '✅ 连接成功' : '❌ ' + testResult.message }}
      </div>
      <template #footer>
        <el-button @click="testConnection" :loading="testing">测试连接</el-button>
        <el-button type="primary" @click="saveAndClose" :loading="saving">保存</el-button>
        <el-button @click="showSettings = false">取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import api from '@/api'
import { ElMessage } from 'element-plus'

const showSettings = ref(false)
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
    const find = (key) => {
      const item = list.find(i => i.keyName === key)
      return item ? item.value || '' : ''
    }
    savedApiKey.value = find('api_key')
    model.value = find('model')
    baseUrl.value = find('base_url')
    // 后端返回的是脱敏后的 Key，直接显示在密码框中，提示用户已保存
    apiKey.value = savedApiKey.value
    testResult.value = null
  } catch (e) { /* 首次使用 */ }
}

// 打开设置弹窗时重新加载，避免显示上次未保存的临时输入
watch(showSettings, (val) => {
  if (val) loadSettings()
})

async function doSave() {
  const keyInput = apiKey.value.trim()
  const payload = [
    { keyName: 'model', value: model.value },
    { keyName: 'base_url', value: baseUrl.value },
  ]
  // 只有用户真正填写了新的 Key（与加载回来的脱敏值不同）时才提交，避免用掩码覆盖真实 Key
  if (keyInput && keyInput !== savedApiKey.value) {
    payload.push({ keyName: 'api_key', value: keyInput })
    savedApiKey.value = keyInput
  }
  if (payload.length) {
    await api.put('/settings', payload)
  }
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    await doSave()
    const res = await api.post('/settings/test')
    testResult.value = res.data  // { success: bool, message: string }
  } catch (e) {
    testResult.value = { success: false, message: e.response?.data?.message || e.message || '网络错误' }
  } finally {
    testing.value = false
  }
}

async function saveAndClose() {
  saving.value = true
  try {
    await doSave()
    showSettings.value = false
    ElMessage.success('设置已保存')
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => loadSettings())
</script>

<style>
body {
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', Arial, sans-serif;
}
.app-header {
  display: flex;
  align-items: center;
  padding: 0 20px;
  height: 60px;
  border-bottom: none !important;
}
.app-title {
  font-size: 18px;
  font-weight: 600;
  margin-right: 30px;
  white-space: nowrap;
  width: 240px;
  flex-shrink: 0;
  cursor: pointer;
  user-select: none;
}
.app-title:hover { color: #409eff; }
.app-nav {
  flex: 1;
  border-bottom: none !important;
  max-width: 50%;
}
.app-nav .el-menu-item {
  font-size: 16px;
  font-weight: 600;
}
.el-main {
  padding: 0 !important;
}
.settings-btn {
  margin-left: auto;
  padding: 6px 8px !important;
  min-width: auto !important;
  color: #606266;
}
.settings-btn:hover { color: #409eff; }
.test-result {
  padding: 8px 12px; border-radius: 6px; font-size: 13px; margin-top: 8px;
}
.test-result.success { background: #f0f9eb; color: #67c23a; }
.test-result.fail { background: #fef0f0; color: #f56c6c; }
</style>
