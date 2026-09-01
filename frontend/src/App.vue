<template>
  <el-config-provider :message="globalMessageConfig">
  <div class="app-container" :class="{ 'is-dashboard': isDashboard, 'is-nav-open': navOverlayOpen }">
    <aside class="app-sidebar" :class="{ 'is-expanded': isDashboard || navOverlayOpen }">
      <button class="app-brand" type="button" title="返回看板" @click="goTo('/')">
        <span class="brand-mark" aria-hidden="true">
          <img class="brand-mark__light" :src="researchAssistantLogoLightUrl" alt="" />
          <img class="brand-mark__dark" :src="researchAssistantLogoDarkUrl" alt="" />
        </span>
        <span class="brand-copy"><b>Research</b><small>Assistant</small></span>
      </button>

      <button
        v-if="!isDashboard"
        class="nav-collapse-toggle"
        type="button"
        :title="navOverlayOpen ? '收起导航' : '展开导航'"
        :aria-expanded="navOverlayOpen"
        @click="navOverlayOpen = !navOverlayOpen"
      >
        <el-icon><Fold v-if="navOverlayOpen" /><Expand v-else /></el-icon>
        <span>{{ navOverlayOpen ? '收起导航' : '展开导航' }}</span>
      </button>

      <nav class="sidebar-nav" aria-label="主导航">
        <button
          v-for="item in navigationItems"
          :key="item.route"
          type="button"
          class="sidebar-nav-item"
          :class="{ 'is-active': routeIsActive(item.route) }"
          :title="`${item.label} (${item.shortcut})`"
          @click="goTo(item.route)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="sidebar-footer">
        <div class="sidebar-utility-actions">
          <button class="sidebar-nav-item" type="button" :title="dark.dark ? '切换亮色' : '切换暗色'" @click="toggleTheme">
            <el-icon><Moon v-if="dark.dark" /><Sunny v-else /></el-icon>
            <span>{{ dark.dark ? '切换亮色' : '切换暗色' }}</span>
          </button>
          <button class="sidebar-nav-item" type="button" title="API 设置" @click="showSettings = true">
            <el-icon><Setting /></el-icon><span>API 设置</span>
          </button>
          <button class="sidebar-nav-item" type="button" title="快捷键 (?)" @click="showShortcuts = true">
            <el-icon><QuestionFilled /></el-icon><span>快捷键</span>
          </button>
        </div>
        <div class="sidebar-task-actions">
          <button
            class="sidebar-nav-item"
            :class="{ 'is-active': routeIsActive('/tasks') }"
            type="button"
            title="任务中心"
            @click="toggleTaskCenter"
          >
            <el-icon><Tickets /></el-icon><span>任务中心</span>
          </button>
        </div>
      </div>
    </aside>
    <button v-if="!isDashboard && navOverlayOpen" class="nav-scrim" type="button" aria-label="收起导航" @click="navOverlayOpen = false"></button>

    <section class="app-content-shell">
      <main class="app-main">
        <CachedRouterView :include="['LibraryView', 'PaperResearchView']" />
      </main>
    </section>

    <!-- ====== 设置弹窗 ====== -->
    <el-dialog
      v-model="showSettings"
      title="API 设置"
      width="620px"
      :close-on-click-modal="false"
      append-to-body
      :z-index="20020"
      class="api-settings-dialog"
    >
      <div class="api-role-switch" :class="{ 'is-document': activeApiRole === 'DOCUMENT' }" role="tablist" aria-label="API 类型">
        <span class="api-role-switch__indicator" aria-hidden="true" />
        <button type="button" role="tab" :aria-selected="activeApiRole === 'CHAT'" @click="activeApiRole = 'CHAT'">对话 API</button>
        <button type="button" role="tab" :aria-selected="activeApiRole === 'DOCUMENT'" @click="activeApiRole = 'DOCUMENT'">解析 API</button>
      </div>
      <p class="api-role-description">
        {{ activeApiRole === 'CHAT'
          ? '用于日常对话和 Agent 工具调用。接口需要支持连续 Tool Calling。'
          : '用于图片、PDF 和 Word 附件理解。接口必须支持图片，原生 PDF 能力由测试自动检测。' }}
      </p>
      <el-form label-position="top" class="api-role-form">
        <el-form-item label="接口地址（URL）">
          <el-input v-model="activeBaseUrl" placeholder="https://api.example.com/v1" />
        </el-form-item>
        <el-form-item label="模型" class="model-catalog-form-item">
          <div class="model-catalog-control">
            <el-input v-model="activeModel" placeholder="输入模型名称" />
            <el-button :loading="queryingModels" @click="queryAvailableModels">查询可用模型</el-button>
          </div>
          <section v-if="modelCatalogVisible" class="model-catalog-panel" aria-label="可用模型">
            <div v-if="availableModels.length" class="model-catalog-list">
              <button
                v-for="item in availableModels"
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
              <span class="model-catalog-status__dot" aria-hidden="true"></span>
              <span>{{ modelCatalogStatus }}</span>
            </footer>
          </section>
        </el-form-item>
        <el-form-item label="接口密钥（API Key）">
          <el-input v-model="activeApiKey" type="password" show-password placeholder="留空表示不更改已保存密钥" />
        </el-form-item>
      </el-form>
      <p class="api-protocol-hint">调用协议：{{ activeProtocolHint }}</p>
      <div v-if="testResult !== null" class="test-result" :class="{ success: testResult.success, fail: !testResult.success }">
        <div>{{ testResult.success ? '✅ ' : '❌ ' }}{{ testResult.message }}</div>
        <div v-if="testResult.capabilities" class="capability-list">
          <span v-for="(value, key) in testResult.capabilities" :key="key">{{ capabilityLabel(key) }}：{{ value }}</span>
        </div>
      </div>
      <template #footer>
        <el-button
          @click="activeApiRole === 'CHAT' ? testConnection() : testDocumentConnection()"
          :loading="activeApiRole === 'CHAT' ? testing : testingDocument"
        >{{ activeApiRole === 'CHAT' ? '测试对话能力' : '测试解析能力' }}</el-button>
        <el-button type="primary" @click="saveAndClose" :loading="saving">保存设置</el-button>
      </template>
    </el-dialog>
    <!-- ====== 命令面板 ====== -->
    <CommandPalette v-model="showPalette" :commands="commands" @execute="onCommandExecute" />

    <!-- ====== 快捷键 ====== -->
    <el-dialog
      v-model="showShortcuts"
      title="键盘快捷键"
      width="480px"
      align-center
      append-to-body
      :z-index="20020"
    >
      <el-table :data="shortcutList" size="small" :show-header="true" border>
        <el-table-column prop="desc" label="操作" />
        <el-table-column prop="keys" label="快捷键" width="140" />
      </el-table>
    </el-dialog>
  </div>
  </el-config-provider>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '@/api'
import { ElMessage } from 'element-plus'
import {
  Collection, Document, Expand, Fold, House, Moon, QuestionFilled,
  Reading, Search, Setting, Sunny, Tickets,
} from '@element-plus/icons-vue'
import { useTheme } from '@/stores/themeStore'
import { useKeyboardShortcuts } from '@/composables/useKeyboardShortcuts'
import CommandPalette from '@/components/CommandPalette.vue'
import CachedRouterView from '@/components/navigation/CachedRouterView.vue'
import { taskCenterNavigation } from '@/utils/taskCenterNavigation.js'
import researchAssistantLogoLightUrl from '@/assets/research-assistant-logo-d3.png'
import researchAssistantLogoDarkUrl from '@/assets/research-assistant-logo-d3-dark.png'

const router = useRouter()
const route = useRoute()
const { dark, toggle: toggleTheme } = useTheme()

const navOverlayOpen = ref(false)
const taskCenterReturnPath = ref('')
const isDashboard = computed(() => route.path === '/')
const globalMessageConfig = Object.freeze({ max: 2, duration: 1800, grouping: true })
const navigationItems = [
  { label: '看板', route: '/', shortcut: 'Ctrl+1', icon: House },
  { label: '文库管理', route: '/library', shortcut: 'Ctrl+2', icon: Collection },
  { label: '文献检索', route: '/search', shortcut: 'Ctrl+3', icon: Search },
  { label: '论文助手', route: '/research', shortcut: 'Ctrl+4', icon: Reading },
  { label: '研究档案', route: '/archive', shortcut: 'Ctrl+5', icon: Document },
]

function routeIsActive(target) {
  if (target === '/') return route.path === '/'
  return route.path.startsWith(target)
}

function goTo(target) {
  navOverlayOpen.value = false
  router.push(target)
}

function toggleTaskCenter() {
  navOverlayOpen.value = false
  const navigation = taskCenterNavigation(route.fullPath, taskCenterReturnPath.value)
  taskCenterReturnPath.value = navigation.nextReturnPath
  router.push(navigation.target)
}

watch(() => route.fullPath, () => { navOverlayOpen.value = false })

const showSettings = ref(false)
const showShortcuts = ref(false)
const showPalette = ref(false)
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
const availableModels = computed(() => activeApiRole.value === 'CHAT'
  ? chatAvailableModels.value : documentAvailableModels.value)
const activeProtocolHint = computed(() => {
  if (activeApiRole.value === 'CHAT') return 'OpenAI Compatible（自动）'
  const url = documentBaseUrl.value.toLowerCase()
  return url.includes('generativelanguage.googleapis.com') || /\/v1(?:beta|alpha)(?:\/|$)/.test(url)
    ? 'Gemini Native（根据 URL 自动识别）'
    : 'OpenAI Compatible（根据 URL 自动识别）'
})

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
    documentBaseUrl.value = find('document_base_url')
    documentModel.value = find('document_model')
    savedDocumentApiKey.value = find('document_api_key')
    documentApiKey.value = savedDocumentApiKey.value
    // 后端返回的是脱敏后的 Key，直接显示在密码框中，提示用户已保存
    apiKey.value = savedApiKey.value
    testResult.value = null
    resetModelCatalog()
  } catch (e) { /* 首次使用 */ }
}

// 打开设置弹窗时重新加载，避免显示上次未保存的临时输入
watch(showSettings, (val) => {
  if (val) loadSettings()
})
watch(activeApiRole, () => {
  testResult.value = null
  resetModelCatalog()
})

async function doSave() {
  const keyInput = apiKey.value.trim()
  const payload = [
    { keyName: 'model', value: model.value.trim() },
    { keyName: 'base_url', value: baseUrl.value.trim() },
    { keyName: 'document_base_url', value: documentBaseUrl.value.trim() },
    { keyName: 'document_model', value: documentModel.value.trim() },
  ]
  // 只有用户真正填写了新的 Key（与加载回来的脱敏值不同）时才提交，避免用掩码覆盖真实 Key
  if (keyInput && keyInput !== savedApiKey.value) {
    payload.push({ keyName: 'api_key', value: keyInput })
    savedApiKey.value = keyInput
  }
  const documentKeyInput = documentApiKey.value.trim()
  if (documentKeyInput && documentKeyInput !== savedDocumentApiKey.value) {
    payload.push({ keyName: 'document_api_key', value: documentKeyInput })
    savedDocumentApiKey.value = documentKeyInput
  }
  if (payload.length) {
    await api.put('/settings', payload)
  }
}

function resetModelCatalog() {
  modelCatalogVisible.value = false
  modelCatalogError.value = false
  modelCatalogStatus.value = ''
}

function validateActiveApiSettings() {
  if (!activeBaseUrl.value.trim()) {
    ElMessage.warning('请先填写接口地址')
    return false
  }
  if (!activeModel.value.trim()) {
    ElMessage.warning('请先填写模型')
    return false
  }
  const savedKey = activeApiRole.value === 'CHAT' ? savedApiKey.value : savedDocumentApiKey.value
  if (!activeApiKey.value.trim() && !savedKey) {
    ElMessage.warning('请先填写 API Key')
    return false
  }
  return true
}

async function queryAvailableModels() {
  if (!activeBaseUrl.value.trim()) {
    ElMessage.warning('请先填写 Base URL')
    return
  }
  const savedKey = activeApiRole.value === 'CHAT' ? savedApiKey.value : savedDocumentApiKey.value
  if (!activeApiKey.value.trim() && !savedKey) {
    ElMessage.warning('请先填写 API Key')
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
    const models = Array.isArray(res.data?.models) ? res.data.models : []
    if (activeApiRole.value === 'CHAT') chatAvailableModels.value = models
    else documentAvailableModels.value = models
    modelCatalogStatus.value = `已获取 ${availableModels.value.length} 个可用模型`
  } catch (e) {
    modelCatalogError.value = true
    modelCatalogStatus.value = e.response?.data?.message || e.message || '查询可用模型失败'
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

async function testConnection() {
  if (!validateActiveApiSettings()) return
  testing.value = true
  testResult.value = null
  try {
    const res = await api.post('/settings/capabilities/CHAT/test', capabilityTestPayload('CHAT'))
    testResult.value = {
      success: res.data?.status === 'VERIFIED',
      message: res.data?.status === 'VERIFIED' ? 'Agent 连续工具调用已验证' : (res.data?.errorMessage || '能力测试失败'),
      capabilities: {
        toolCalling: res.data?.toolCalling ? '已验证' : '失败',
        continuousTools: res.data?.continuousTools ? '已验证' : '失败',
      },
    }
  } catch (e) {
    testResult.value = { success: false, message: e.response?.data?.message || e.message || '网络错误' }
  } finally {
    testing.value = false
  }
}

async function testDocumentConnection() {
  if (!validateActiveApiSettings()) return
  testingDocument.value = true
  testResult.value = null
  try {
    const res = await api.post('/settings/capabilities/DOCUMENT/test', capabilityTestPayload('DOCUMENT'))
    testResult.value = {
      success: res.data?.status === 'VERIFIED',
      message: res.data?.status === 'VERIFIED'
        ? `图片输入已验证；原生 PDF ${res.data?.pdf ? '已启用' : '不支持，将使用结构化文本'}`
        : (res.data?.errorMessage || '论文解析模型测试失败'),
    }
  } catch (e) {
    testResult.value = { success: false, message: e.response?.data?.message || e.message || '论文解析模型测试失败' }
  } finally {
    testingDocument.value = false
  }
}

function capabilityTestPayload(role) {
  const enteredKey = (role === 'CHAT' ? apiKey.value : documentApiKey.value).trim()
  const savedKey = role === 'CHAT' ? savedApiKey.value : savedDocumentApiKey.value
  return {
    baseUrl: (role === 'CHAT' ? baseUrl.value : documentBaseUrl.value).trim(),
    model: (role === 'CHAT' ? model.value : documentModel.value).trim(),
    // Keep a saved masked key out of the request; the backend resolves it from
    // persisted settings. A newly entered key is used only for this probe.
    apiKey: enteredKey && enteredKey !== savedKey ? enteredKey : '',
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

const routeCommands = [
  { id: 'dashboard', title: '打开看板', subtitle: '首页数据面板', route: '/', shortcut: 'Ctrl+1', shortcutKey: '1', keywords: ['看板', 'dashboard', '首页'] },
  { id: 'library', title: '打开文库管理', subtitle: '论文库与文件夹', route: '/library', shortcut: 'Ctrl+2', shortcutKey: '2', keywords: ['文库', 'library', '论文'] },
  { id: 'search', title: '打开文献检索', subtitle: 'AI 检索与多源搜索', route: '/search', shortcut: 'Ctrl+3', shortcutKey: '3', keywords: ['检索', 'search', '文献'] },
  { id: 'workbench', title: '打开论文助手', subtitle: '基于论文理解的连续科研对话', route: '/research', shortcut: 'Ctrl+4', shortcutKey: '4', keywords: ['助手', '对话', '分析', 'analysis', '论文助手'] },
  { id: 'archive', title: '打开研究档案', subtitle: '对话、分析与证据记录', route: '/archive', shortcut: 'Ctrl+5', shortcutKey: '5', keywords: ['档案', 'archive', '研究', '对话'] },
]

const commands = [
  ...routeCommands,
  { id: 'tasks', title: '查看后台任务', subtitle: '异步任务与工作流', route: '/tasks', shortcut: '', keywords: ['任务', 'task', '工作流'] },
  { id: 'search-quick', title: '全局搜索论文…', subtitle: '跳转到文献检索', action: () => router.push('/search'), shortcut: 'Ctrl+Shift+F', keywords: ['搜索', 'search', '论文'] },
  { id: 'settings', title: '打开设置', subtitle: 'API 与模型配置', action: () => { showSettings.value = true }, shortcut: '', keywords: ['设置', 'settings', 'api'] },
  { id: 'theme', title: '切换主题', subtitle: '亮色 / 暗色', action: toggleTheme, shortcut: '', keywords: ['主题', 'theme', '暗色', '亮色'] },
]

function onCommandExecute(cmd) {
  if (cmd.route) router.push(cmd.route)
  else if (cmd.action) cmd.action()
}

const shortcutList = [
  { desc: '打开命令面板', keys: 'Ctrl + K' },
  { desc: '全局搜索论文', keys: 'Ctrl + Shift + F' },
  { desc: '打开看板', keys: 'Ctrl + 1' },
  { desc: '打开文库管理', keys: 'Ctrl + 2' },
  { desc: '打开文献检索', keys: 'Ctrl + 3' },
  { desc: '打开论文助手', keys: 'Ctrl + 4' },
  { desc: '打开研究档案', keys: 'Ctrl + 5' },
]

useKeyboardShortcuts([
  { key: 'k', ctrl: true, whenTyping: false, action: () => { showPalette.value = true } },
  { key: 'F', ctrl: true, shift: true, whenTyping: false, action: () => router.push('/search') },
  ...routeCommands.map(cmd => ({
    key: cmd.shortcutKey,
    ctrl: true,
    whenTyping: false,
    action: () => router.push(cmd.route)
  })),
])

onMounted(() => {
  loadSettings()
})
</script>

<style>
:root {
  --ra-bg: #f5f5f7;
  --ra-panel-bg: #ffffff;
  --ra-header-bg: rgba(255, 255, 255, .82);
  --ra-sidebar-bg: #f0f0f2;
  --ra-text: #1d1d1f;
  --ra-text-secondary: #5f6065;
  --ra-text-tertiary: #8e8e93;
  --ra-border: #d8d8dc;
  --ra-border-light: #e8e8eb;
  --ra-link: #0071e3;
  --ra-hover-bg: rgba(0, 0, 0, .045);
  --ra-active-bg: rgba(0, 113, 227, .1);
  --ra-active-text: #0066cc;
  --ra-shadow: 0 12px 40px rgba(0, 0, 0, .08);
}
html.dark {
  --ra-bg: #111214;
  --ra-panel-bg: #1c1d20;
  --ra-header-bg: rgba(28, 29, 32, .84);
  --ra-sidebar-bg: #18191c;
  --ra-text: #f5f5f7;
  --ra-text-secondary: #b0b0b5;
  --ra-text-tertiary: #7f8087;
  --ra-border: #36373c;
  --ra-border-light: #2b2c31;
  --ra-link: #2997ff;
  --ra-hover-bg: rgba(255, 255, 255, .06);
  --ra-active-bg: rgba(41, 151, 255, .16);
  --ra-active-text: #64b5ff;
  --ra-shadow: 0 18px 55px rgba(0, 0, 0, .38);
}
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', 'Helvetica Neue', 'PingFang SC', sans-serif;
  background: var(--ra-bg);
  color: var(--ra-text);
}
html.pdf-viewer-open,
body.pdf-viewer-open {
  overflow: hidden;
  overscroll-behavior: none;
}

/* Element Plus 暗色模式全局覆盖：保留语义色标签的辨识度 */
html.dark .el-tag--info { --el-tag-bg-color: #3a3c42; --el-tag-text-color: #b8bac1; --el-tag-border-color: #4c4e55; }
html.dark .el-tag--success { --el-tag-bg-color: #2a3b26; --el-tag-text-color: #85ce61; --el-tag-border-color: #3d5636; }
html.dark .el-tag--warning { --el-tag-bg-color: #3f3019; --el-tag-text-color: #eebe77; --el-tag-border-color: #5c4624; }
html.dark .el-tag--danger { --el-tag-bg-color: #3b1e1e; --el-tag-text-color: #f89898; --el-tag-border-color: #5c2f2f; }
html.dark .el-tag--primary { --el-tag-bg-color: #1e3348; --el-tag-text-color: #79bbff; --el-tag-border-color: #2e4a66; }

/* vxe-table 暗色模式适配 */
html.dark .vxe-table {
  --vxe-ui-table-border-color: var(--ra-border-light);
  --vxe-ui-table-header-background-color: var(--ra-panel-bg);
  color: var(--ra-text);
  border-color: var(--ra-border-light) !important;
}
html.dark .vxe-table .vxe-header--column { background-color: var(--ra-header-bg); border-color: var(--ra-border); color: var(--ra-text) !important; }
html.dark .vxe-table .vxe-sort--asc-btn,
html.dark .vxe-table .vxe-sort--desc-btn { color: var(--ra-text-tertiary) !important; }
html.dark .vxe-table .vxe-sort--asc-btn.sort--active,
html.dark .vxe-table .vxe-sort--desc-btn.sort--active { color: var(--ra-text-tertiary) !important; }
html.dark .vxe-table .vxe-body--column { background-color: var(--ra-panel-bg); border-color: var(--ra-border-light); }
html.dark .vxe-table .vxe-table--layout-wrapper,
html.dark .vxe-table .vxe-table--header-wrapper,
html.dark .vxe-table .vxe-table--body-wrapper { background-color: var(--ra-panel-bg) !important; }
html.dark .vxe-table .vxe-body--row.row--hover .vxe-body--column { background-color: var(--ra-hover-bg); }
html.dark .vxe-table .vxe-body--row.row--current .vxe-body--column { background-color: var(--ra-active-bg); }
html.dark .vxe-pager { background-color: transparent; color: var(--ra-text-secondary); }
html.dark .vxe-pager .vxe-pager--btn-wrapper .vxe-pager--num-btn:not(.vxe-pager--num-btn--active),
html.dark .vxe-pager .vxe-pager--prev-btn,
html.dark .vxe-pager .vxe-pager--next-btn { color: var(--ra-text-secondary); }
html.dark .vxe-pager .vxe-pager--num-btn--active { background-color: var(--ra-active-bg); color: var(--ra-active-text); border-color: var(--ra-border); }
html.dark .vxe-pager button,
html.dark .vxe-pager input { border-color: var(--ra-border) !important; background: var(--ra-panel-bg) !important; color: var(--ra-text-secondary) !important; }

/* Element Plus 弹窗/下拉/输入框暗色微调 */
html.dark .el-dialog { --el-dialog-bg-color: var(--ra-panel-bg); }
html.dark .el-dropdown__popper.el-popper { --el-dropdown-menuItem-hover-fill: var(--ra-hover-bg); }
html.dark .el-textarea__inner,
html.dark .el-input__wrapper { --el-input-bg-color: var(--ra-panel-bg); --el-input-border-color: var(--ra-border); }
html.dark .el-tree { --el-tree-node-hover-bg-color: var(--ra-hover-bg); --el-tree-text-color: var(--ra-text); }
html.dark .el-card { --el-card-bg-color: var(--ra-panel-bg); --el-card-border-color: var(--ra-border); }
.app-container {
  min-height: 100vh;
  background: var(--ra-bg);
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  overflow: hidden;
  transition: grid-template-columns .24s cubic-bezier(.4, 0, .2, 1);
}
.app-container.is-dashboard { grid-template-columns: 184px minmax(0, 1fr); }
.app-sidebar {
  position: relative;
  z-index: 110;
  display: flex;
  width: 52px;
  height: 100vh;
  box-sizing: border-box;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--ra-border-light);
  background: var(--ra-sidebar-bg);
  color: var(--ra-text);
  transition: width .24s cubic-bezier(.4, 0, .2, 1), box-shadow .24s ease;
}
.app-sidebar.is-expanded { width: 184px; }
.app-container:not(.is-dashboard) .app-sidebar.is-expanded {
  position: fixed;
  inset: 0 auto 0 0;
  box-shadow: var(--ra-shadow);
}
.nav-scrim {
  position: fixed;
  z-index: 100;
  inset: 0;
  padding: 0;
  border: 0;
  background: rgba(0, 0, 0, .16);
}
.app-brand,
.nav-collapse-toggle,
.sidebar-nav-item {
  display: flex;
  width: calc(100% - 12px);
  min-height: 40px;
  box-sizing: border-box;
  margin: 0 6px;
  padding: 0 11px;
  align-items: center;
  gap: 12px;
  overflow: hidden;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--ra-text-secondary);
  font: inherit;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
}
.app-brand { min-height: 58px; margin-top: 2px; color: var(--ra-text); }
.app-sidebar:not(.is-expanded) .app-brand,
.app-sidebar:not(.is-expanded) .nav-collapse-toggle,
.app-sidebar:not(.is-expanded) .sidebar-nav-item {
  justify-content: center;
  gap: 0;
  padding-right: 0;
  padding-left: 0;
}
.app-sidebar:not(.is-expanded) .brand-copy,
.app-sidebar:not(.is-expanded) .nav-collapse-toggle > span,
.app-sidebar:not(.is-expanded) .sidebar-nav-item > span {
  display: none;
}
.app-sidebar:not(.is-expanded) .brand-mark {
  transform: translateX(1px);
}
.app-brand:focus { outline:none; }
.app-brand:focus-visible { outline:none; box-shadow:none; }
.nav-collapse-toggle:focus,
.nav-collapse-toggle:focus-visible { outline:none; box-shadow:none; }
.brand-mark {
  position: relative;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  overflow: hidden;
  box-sizing: border-box;
}
.brand-mark img {
  position:absolute;
  top:50%;
  left:50%;
  display:block;
  width:30px;
  height:30px;
  max-width:none;
  object-fit:contain;
  transform:translate(-50%, -50%);
  transition:opacity .12s ease;
}
.brand-mark__dark { opacity:0; }
html.dark .brand-mark__light { opacity:0; }
html.dark .brand-mark__dark { opacity:1; }
.brand-copy { display:flex; min-width:0; flex-direction:column; align-items:flex-start; gap:2px; line-height:1; opacity:0; transition:opacity .12s ease; }
.brand-copy b { color:var(--ra-text); font-size:16px; font-weight:650; letter-spacing:-.3px; }
.brand-copy small { color:var(--ra-text-tertiary); font-size:13px; font-weight:550; letter-spacing:-.1px; }
.app-sidebar.is-expanded .brand-copy { opacity: 1; }
.nav-collapse-toggle { margin-top: 2px; color: var(--ra-text-tertiary); }
.sidebar-nav { display: flex; flex: 1; flex-direction: column; gap: 4px; padding-top: 10px; }
.sidebar-footer { display: flex; flex-direction: column; padding: 8px 0 14px; }
.sidebar-utility-actions,
.sidebar-task-actions { display: flex; flex-direction: column; gap: 4px; }
.sidebar-task-actions { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--ra-border-light); }
.sidebar-nav-item .el-icon,
.nav-collapse-toggle .el-icon { width: 20px; height: 20px; flex: 0 0 20px; font-size: 20px; }
.sidebar-nav-item span,
.nav-collapse-toggle span { opacity: 0; transition: opacity .12s ease; }
.app-sidebar.is-expanded .sidebar-nav-item span,
.app-sidebar.is-expanded .nav-collapse-toggle span { opacity: 1; }
.sidebar-nav-item:hover,
.nav-collapse-toggle:hover,
.app-brand:hover { background: var(--ra-hover-bg); color: var(--ra-text); }
.sidebar-nav-item.is-active { background: var(--ra-active-bg); color: var(--ra-active-text); font-weight: 600; }
.app-content-shell { position:relative; grid-column:2; min-width: 0; height: 100vh; overflow: hidden; }
.app-main {
  height: 100vh;
  min-width: 0;
  overflow: auto;
  background: var(--ra-bg);
}
.test-result {
  padding: 8px 12px; border-radius: 6px; font-size: 13px; margin-top: 8px;
}
.api-settings-dialog .el-dialog__body { padding-top: 18px; }
.api-role-switch {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  height: 48px;
  margin-bottom: 20px;
  overflow: hidden;
  border: 1px solid var(--ra-border);
  border-radius: 12px;
  background: var(--ra-bg);
}
.api-role-switch__indicator {
  position: absolute;
  inset: 3px 50% 3px 3px;
  border: 1px solid color-mix(in srgb, var(--ra-link) 72%, transparent);
  border-radius: 9px;
  background: var(--ra-active-bg);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--ra-link) 28%, transparent);
  transition: transform .2s ease;
}
.api-role-switch.is-document .api-role-switch__indicator { transform: translateX(calc(100% + 3px)); }
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
.api-role-switch button[aria-selected="true"] { color: var(--ra-text); }
.api-role-description {
  min-height: 42px;
  margin: 0 0 18px;
  color: var(--ra-text-tertiary);
  font-size: 13px;
  line-height: 1.6;
}
.api-role-form .el-form-item { margin-bottom: 18px; }
.api-role-form .el-form-item__label { color: var(--ra-text-secondary); font-weight: 600; }
.api-protocol-hint { margin: -4px 0 14px; color: var(--ra-text-tertiary); font-size: 12px; }
.test-result.success { background: #f0f9eb; color: #67c23a; }
.test-result.fail { background: #fef0f0; color: #f56c6c; }
.model-catalog-form-item .el-form-item__content { display: block; }
.model-catalog-control { display: flex; width: 100%; gap: 10px; }
.model-catalog-control .el-input { min-width: 0; flex: 1; }
.model-catalog-control .el-button { flex: 0 0 auto; }
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
.capability-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 8px;
  font-size: 12px;
}
.advanced-settings {
  width: 100%;
  border-top: none;
}
html.dark .test-result.success { background: #1e3924; color: #85ce61; }
html.dark .test-result.fail { background: #3b1e1e; color: #f89898; }

@media (max-width: 760px) {
  .app-container.is-dashboard { grid-template-columns: 52px minmax(0, 1fr); }
  .app-container.is-dashboard .app-sidebar { width: 52px; }
  .app-container.is-dashboard .app-brand,
  .app-container.is-dashboard .sidebar-nav-item {
    justify-content: center;
    gap: 0;
    padding-right: 0;
    padding-left: 0;
  }
  .app-container.is-dashboard .brand-copy,
  .app-container.is-dashboard .sidebar-nav-item > span { display: none; }
  .app-container.is-dashboard .brand-mark { transform: translateX(1px); }
}
</style>
