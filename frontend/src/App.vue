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
          <el-menu-item index="/" title="看板 (Ctrl+1)">看板</el-menu-item>
          <el-menu-item index="/library" title="文库管理 (Ctrl+2)">文库管理</el-menu-item>
          <el-menu-item index="/search" title="文献检索 (Ctrl+3)">文献检索</el-menu-item>
          <el-menu-item index="/workbench" title="论文研究 (Ctrl+4)">论文研究</el-menu-item>
          <el-menu-item index="/tasks" title="任务中心 (Ctrl+6)">任务中心</el-menu-item>
          <el-menu-item index="/reading-plans" title="阅读计划 (Ctrl+7)">阅读计划</el-menu-item>
          <el-menu-item index="/writing" title="写作助手 (Ctrl+8)">写作助手</el-menu-item>
        </el-menu>
        <div class="header-actions">
          <el-button text class="theme-btn" @click="toggleTheme" :title="dark.dark ? '切换亮色' : '切换暗色'">
          <el-icon v-if="dark.dark" :size="18"><Moon /></el-icon>
          <el-icon v-else :size="18"><Sunny /></el-icon>
        </el-button>
        <el-button text class="settings-btn" @click="showSettings = true" title="设置">
          <svg width="18" height="18" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 10a2 2 0 100-4 2 2 0 000 4z"/><path d="M14.46 6.54l-1.08-.42a5.1 5.1 0 00-.56-1.36l.42-1.08a.5.5 0 00-.12-.62l-.92-.92a.5.5 0 00-.62-.12l-1.08.42a5.1 5.1 0 00-1.36-.56L8.72 1.54a.5.5 0 00-.46-.34h-1.3a.5.5 0 00-.46.34l-.42 1.08a5.1 5.1 0 00-1.36.56l-1.08-.42a.5.5 0 00-.62.12l-.92.92a.5.5 0 00-.12.62l.42 1.08a5.1 5.1 0 00-.56 1.36l-1.08.42a.5.5 0 00-.34.46v1.3a.5.5 0 00.34.46l1.08.42c.1.48.3.94.56 1.36l-.42 1.08a.5.5 0 00.12.62l.92.92a.5.5 0 00.62.12l1.08-.42c.42.26.88.46 1.36.56l.42 1.08a.5.5 0 00.46.34h1.3a.5.5 0 00.46-.34l.42-1.08c.48-.1.94-.3 1.36-.56l1.08.42a.5.5 0 00.62-.12l.92-.92a.5.5 0 00.12-.62l-.42-1.08c.26-.42.46-.88.56-1.36l1.08-.42a.5.5 0 00.34-.46v-1.3a.5.5 0 00-.34-.46z"/></svg>
        </el-button>
        <el-button text class="help-btn" @click="showShortcuts = true" title="快捷键帮助 (?)">
          <svg width="18" height="18" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="8" cy="8" r="6"/><path d="M6 6a2 2 0 0 1 2-2 2 2 0 0 1 2 2c0 1.5-2 2-2 3.5"/><circle cx="8" cy="11.5" r="0.8" fill="currentColor" stroke="none"/></svg>
        </el-button>
        </div>
      </el-header>
      <div class="app-divider"></div>
      <el-main>
        <CachedRouterView :include="['LibraryView']" />
      </el-main>
    </el-container>

    <!-- ====== 设置弹窗 ====== -->
    <el-dialog v-model="showSettings" title="API 设置" width="480px" :close-on-click-modal="false">
      <p style="font-size:12px;color:var(--ra-text-tertiary);margin:0 0 16px">
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
        <el-form-item label="研究主题">
          <el-input v-model="researchTopic" type="textarea" :rows="2" placeholder="例如：多模态大模型在医疗影像中的应用" size="default" />
          <p style="font-size:12px;color:var(--ra-text-tertiary);margin:6px 0 0;line-height:1.5">
            用于分析论文与本研究方向的匹配度，影响入库时的相关性评分与推荐理由。
          </p>
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
    <!-- ====== 命令面板 ====== -->
    <CommandPalette v-model="showPalette" :commands="commands" @execute="onCommandExecute" />

    <!-- ====== 快捷键帮助 ====== -->
    <el-dialog v-model="showShortcuts" title="键盘快捷键" width="480px" align-center>
      <el-table :data="shortcutList" size="small" :show-header="true" border>
        <el-table-column prop="desc" label="操作" />
        <el-table-column prop="keys" label="快捷键" width="140" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { ElMessage } from 'element-plus'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '@/stores/themeStore'
import { useKeyboardShortcuts } from '@/composables/useKeyboardShortcuts'
import CommandPalette from '@/components/CommandPalette.vue'
import CachedRouterView from '@/components/navigation/CachedRouterView.vue'

const router = useRouter()
const { dark, toggle: toggleTheme } = useTheme()

const showSettings = ref(false)
const showShortcuts = ref(false)
const showPalette = ref(false)
const apiKey = ref('')
const savedApiKey = ref('')
const model = ref('')
const baseUrl = ref('')
const researchTopic = ref('')
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
    researchTopic.value = find('research_topic')
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
    { keyName: 'research_topic', value: researchTopic.value },
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

const routeCommands = [
  { id: 'dashboard', title: '打开看板', subtitle: '首页数据面板', route: '/', shortcut: 'Ctrl+1', shortcutKey: '1', keywords: ['看板', 'dashboard', '首页'] },
  { id: 'library', title: '打开文库管理', subtitle: '论文库与文件夹', route: '/library', shortcut: 'Ctrl+2', shortcutKey: '2', keywords: ['文库', 'library', '论文'] },
  { id: 'search', title: '打开文献检索', subtitle: 'AI 检索与多源搜索', route: '/search', shortcut: 'Ctrl+3', shortcutKey: '3', keywords: ['检索', 'search', '文献'] },
  { id: 'workbench', title: '打开论文研究', subtitle: '全文分析 / Gap / 多篇对比', route: '/workbench', shortcut: 'Ctrl+4', shortcutKey: '4', keywords: ['分析', 'analysis', 'gap', '空白', '对比', '论文研究'] },
  { id: 'tasks', title: '打开任务中心', subtitle: '异步任务与工作流', route: '/tasks', shortcut: 'Ctrl+6', shortcutKey: '6', keywords: ['任务', 'task', '工作流'] },
  { id: 'reading-plans', title: '打开阅读计划', subtitle: '阅读计划与提醒', route: '/reading-plans', shortcut: 'Ctrl+7', shortcutKey: '7', keywords: ['阅读', 'reading', '计划'] },
  { id: 'writing', title: '打开写作助手', subtitle: '大纲 / Related Work / 引用', route: '/writing', shortcut: 'Ctrl+8', shortcutKey: '8', keywords: ['写作', 'writing', '大纲'] },
]

const commands = [
  ...routeCommands,
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
  { desc: '打开论文研究', keys: 'Ctrl + 4' },
  { desc: '打开任务中心', keys: 'Ctrl + 6' },
  { desc: '打开阅读计划', keys: 'Ctrl + 7' },
  { desc: '打开写作助手', keys: 'Ctrl + 8' },
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
  --ra-bg: #f5f6f8;
  --ra-panel-bg: #ffffff;
  --ra-header-bg: #ffffff;
  --ra-text: #303133;
  --ra-text-secondary: #606266;
  --ra-text-tertiary: #909399;
  --ra-border: #dcdfe6;
  --ra-border-light: #e4e7ed;
  --ra-link: #409eff;
  --ra-hover-bg: #f0f2f5;
  --ra-active-bg: #ecf5ff;
  --ra-active-text: #1677d2;
}
html.dark {
  --ra-bg: #1a1b1e;
  --ra-panel-bg: #232428;
  --ra-header-bg: #1f2024;
  --ra-text: #e4e5e7;
  --ra-text-secondary: #a8aaaf;
  --ra-text-tertiary: #7c7f84;
  --ra-border: #3c3e44;
  --ra-border-light: #2e3035;
  --ra-link: #79bbff;
  --ra-hover-bg: #2a2c31;
  --ra-active-bg: #203045;
  --ra-active-text: #79bbff;
}
body {
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', Arial, sans-serif;
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
html.dark .vxe-table { color: var(--ra-text); }
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
}
.app-header {
  display: flex;
  align-items: center;
  padding: 0 20px;
  height: 60px;
  border-bottom: none !important;
  background: var(--ra-header-bg);
}
.app-title {
  font-size: 18px;
  font-weight: 600;
  margin-right: 20px;
  white-space: nowrap;
  width: 200px;
  flex-shrink: 0;
  cursor: pointer;
  user-select: none;
  color: var(--ra-text);
}
.app-title:hover { color: var(--ra-link); }
.app-nav {
  flex: 1;
  border-bottom: none !important;
  min-width: 0;
}
html.dark .app-nav.el-menu,
html.dark .app-nav.el-menu--horizontal {
  background-color: var(--ra-header-bg) !important;
}
html.dark .app-nav .el-menu-item {
  background-color: transparent !important;
}
html.dark .app-nav .el-menu-item.is-active {
  background-color: var(--ra-active-bg) !important;
}
.app-nav .el-menu-item {
  font-size: 14px;
  font-weight: 600;
  padding: 0 10px;
}
.app-divider {
  height: 1px;
  background: var(--ra-border);
  flex-shrink: 0;
  position: relative;
  z-index: 10;
}
.el-main {
  padding: 0 !important;
  background: var(--ra-bg);
}
.theme-btn {
  padding: 6px 8px !important;
  min-width: auto !important;
  color: var(--ra-text-secondary);
  overflow: visible !important;
}
.theme-btn:hover { color: var(--ra-link); }
.settings-btn {
  padding: 6px 8px !important;
  min-width: auto !important;
  color: var(--ra-text-secondary);
  overflow: visible !important;
}
.settings-btn:hover { color: var(--ra-link); }
.help-btn {
  padding: 6px 8px !important;
  min-width: auto !important;
  color: var(--ra-text-secondary);
  overflow: visible !important;
}
.help-btn:hover { color: var(--ra-link); }
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
  padding-left: 12px;
}
.header-actions .el-button > svg {
  display: block;
}
.test-result {
  padding: 8px 12px; border-radius: 6px; font-size: 13px; margin-top: 8px;
}
.test-result.success { background: #f0f9eb; color: #67c23a; }
.test-result.fail { background: #fef0f0; color: #f56c6c; }
html.dark .test-result.success { background: #1e3924; color: #85ce61; }
html.dark .test-result.fail { background: #3b1e1e; color: #f89898; }
</style>
