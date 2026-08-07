<template>
  <div class="app-container" :class="{ 'is-dashboard': isDashboard, 'is-nav-open': navOverlayOpen }">
    <aside class="app-sidebar" :class="{ 'is-expanded': isDashboard || navOverlayOpen }">
      <button class="app-brand" type="button" title="返回看板" @click="goTo('/')">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" fill="none">
            <path d="M7 5.5h11.5L25 12v14.5H7z" />
            <path class="brand-mark-accent" d="M18.5 5.5V12H25M11 17h10M11 21h7" />
            <circle class="brand-mark-dot" cx="23.5" cy="23.5" r="4" />
            <path class="brand-mark-dot" d="m26.4 26.4 2.1 2.1" />
          </svg>
        </span>
        <span class="brand-copy"><b>Research Assistant</b></span>
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
          <button class="sidebar-nav-item" type="button" title="任务中心" @click="goTo('/tasks')">
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
      width="560px"
      :close-on-click-modal="false"
      append-to-body
      :z-index="20020"
    >
      <p style="font-size:12px;color:var(--ra-text-tertiary);margin:0 0 16px">
        选择供应商后，系统会应用对应的端点、参数和推理响应规则。
      </p>
      <el-form label-width="100px" label-position="left">
        <el-form-item label="供应商">
          <el-select v-model="aiProvider" style="width:100%" :teleported="false" @change="onProviderChange">
            <el-option v-for="item in AI_PROVIDERS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="接入通道">
          <el-select v-model="aiChannel" style="width:100%" :teleported="false" @change="onChannelChange">
            <el-option v-for="item in channelOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="baseUrl" placeholder="供应商默认地址" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="apiKey" type="password" show-password placeholder="例如 sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" size="default" />
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="model" filterable allow-create default-first-option :teleported="false" style="width:100%">
            <el-option v-for="item in modelOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="testResult !== null" class="test-result" :class="{ success: testResult.success, fail: !testResult.success }">
        <div>{{ testResult.success ? '✅ ' : '❌ ' }}{{ testResult.message }}</div>
        <div v-if="testResult.capabilities" class="capability-list">
          <span v-for="(value, key) in testResult.capabilities" :key="key">{{ capabilityLabel(key) }}：{{ value }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="testConnection" :loading="testing">测试连接</el-button>
        <el-button type="primary" @click="saveAndClose" :loading="saving">保存</el-button>
        <el-button @click="showSettings = false">取消</el-button>
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
import {
  AI_PROVIDERS,
  channelDefinition,
  inferChannel,
  inferProvider,
  providerChannels,
  providerModels,
  shouldReplaceBaseUrl,
} from '@/config/aiProviders'

const router = useRouter()
const route = useRoute()
const { dark, toggle: toggleTheme } = useTheme()

const navOverlayOpen = ref(false)
const isDashboard = computed(() => route.path === '/')
const navigationItems = [
  { label: '看板', route: '/', shortcut: 'Ctrl+1', icon: House },
  { label: '文库管理', route: '/library', shortcut: 'Ctrl+2', icon: Collection },
  { label: '文献检索', route: '/search', shortcut: 'Ctrl+3', icon: Search },
  { label: '论文助手', route: '/research', shortcut: 'Ctrl+4', icon: Reading },
  { label: '研究档案', route: '/archive', shortcut: 'Ctrl+5', icon: Document },
  { label: '写作助手', route: '/writing', shortcut: 'Ctrl+6', icon: Document },
]

function routeIsActive(target) {
  if (target === '/') return route.path === '/'
  return route.path.startsWith(target)
}

function goTo(target) {
  navOverlayOpen.value = false
  router.push(target)
}

watch(() => route.fullPath, () => { navOverlayOpen.value = false })

const showSettings = ref(false)
const showShortcuts = ref(false)
const showPalette = ref(false)
const apiKey = ref('')
const savedApiKey = ref('')
const aiProvider = ref('kimi')
const aiChannel = ref('coding')
const model = ref('')
const baseUrl = ref('')
const testing = ref(false)
const saving = ref(false)
const testResult = ref(null)
const channelOptions = computed(() => providerChannels(aiProvider.value))
const modelOptions = computed(() => providerModels(aiProvider.value, aiChannel.value))

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
    aiProvider.value = find('ai_provider') || inferProvider(baseUrl.value, model.value)
    aiChannel.value = find('ai_channel') || inferChannel(aiProvider.value, baseUrl.value)
    if (!baseUrl.value) {
      baseUrl.value = channelDefinition(aiProvider.value, aiChannel.value).baseUrl
    }
    if (!model.value) model.value = providerModels(aiProvider.value, aiChannel.value)[0] || ''
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
    { keyName: 'ai_provider', value: aiProvider.value },
    { keyName: 'ai_channel', value: aiChannel.value },
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

function onProviderChange() {
  const channel = providerChannels(aiProvider.value)[0]
  const replaceUrl = shouldReplaceBaseUrl(baseUrl.value)
  aiChannel.value = channel.value
  if (replaceUrl) baseUrl.value = channel.baseUrl
  model.value = providerModels(aiProvider.value, aiChannel.value)[0] || ''
  testResult.value = null
}

function onChannelChange() {
  if (shouldReplaceBaseUrl(baseUrl.value)) {
    baseUrl.value = channelDefinition(aiProvider.value, aiChannel.value).baseUrl
  }
  model.value = providerModels(aiProvider.value, aiChannel.value)[0] || ''
  testResult.value = null
}

function capabilityLabel(key) {
  return {
    chat: '文本对话',
    stream: '流式输出',
    structured: '结构化输出',
    vision: '图片输入',
  }[key] || key
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

const routeCommands = [
  { id: 'dashboard', title: '打开看板', subtitle: '首页数据面板', route: '/', shortcut: 'Ctrl+1', shortcutKey: '1', keywords: ['看板', 'dashboard', '首页'] },
  { id: 'library', title: '打开文库管理', subtitle: '论文库与文件夹', route: '/library', shortcut: 'Ctrl+2', shortcutKey: '2', keywords: ['文库', 'library', '论文'] },
  { id: 'search', title: '打开文献检索', subtitle: 'AI 检索与多源搜索', route: '/search', shortcut: 'Ctrl+3', shortcutKey: '3', keywords: ['检索', 'search', '文献'] },
  { id: 'workbench', title: '打开论文助手', subtitle: '基于论文理解的连续科研对话', route: '/research', shortcut: 'Ctrl+4', shortcutKey: '4', keywords: ['助手', '对话', '分析', 'analysis', '论文助手'] },
  { id: 'archive', title: '打开研究档案', subtitle: '对话、分析与证据记录', route: '/archive', shortcut: 'Ctrl+5', shortcutKey: '5', keywords: ['档案', 'archive', '研究', '对话'] },
  { id: 'writing', title: '打开写作助手', subtitle: '大纲 / Related Work / 引用', route: '/writing', shortcut: 'Ctrl+6', shortcutKey: '6', keywords: ['写作', 'writing', '大纲'] },
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
  { desc: '打开写作助手', keys: 'Ctrl + 6' },
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
.app-container.is-dashboard { grid-template-columns: 208px minmax(0, 1fr); }
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
.app-sidebar.is-expanded { width: 208px; }
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
.app-sidebar:not(.is-expanded) .app-brand { padding-left:6px; }
.app-brand:focus { outline:none; }
.app-brand:focus-visible { outline:none; box-shadow:none; }
.brand-mark {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  color: var(--ra-text);
}
.brand-mark svg { width:30px; height:30px; overflow:visible; }
.brand-mark svg > path:first-child { fill:var(--ra-panel-bg); stroke:currentColor; stroke-width:1.8; stroke-linejoin:round; }
.brand-mark-accent { stroke:var(--ra-link); stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; }
.brand-mark-dot { fill:var(--ra-panel-bg); stroke:var(--ra-link); stroke-width:1.8; stroke-linecap:round; }
.brand-copy { display:flex; align-items:center; line-height:1; opacity:0; transition:opacity .12s ease; }
.brand-copy b { color:var(--ra-text); font-size:16px; font-weight:650; letter-spacing:-.3px; }
.app-sidebar.is-expanded .brand-copy { opacity: 1; }
.nav-collapse-toggle { margin-top: 2px; color: var(--ra-text-tertiary); }
.sidebar-nav { display: flex; flex: 1; flex-direction: column; gap: 4px; padding-top: 10px; }
.sidebar-footer { display: flex; flex-direction: column; padding: 8px 0 14px; }
.sidebar-utility-actions,
.sidebar-task-actions { display: flex; flex-direction: column; gap: 4px; }
.sidebar-task-actions { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--ra-border-light); }
.sidebar-nav-item .el-icon,
.nav-collapse-toggle .el-icon { width: 18px; height: 18px; flex: 0 0 18px; font-size: 18px; }
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
.test-result.success { background: #f0f9eb; color: #67c23a; }
.test-result.fail { background: #fef0f0; color: #f56c6c; }
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
  .app-container.is-dashboard .brand-copy,
  .app-container.is-dashboard .sidebar-nav-item span { opacity: 0; }
}
</style>
