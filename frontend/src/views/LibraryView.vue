<template>
  <div class="library" :class="{ 'is-resizing': resizing }" @mousemove="onResize" @mouseup="stopResize" @mouseleave="stopResize">

    <!-- ==================== 左栏 ==================== -->
    <div class="left-panel" :class="{ collapsed: !sidebarVisible }" :style="{ width: sidebarVisible ? leftWidth + 'px' : '0px' }">
      <div class="panel-header">
        <el-tooltip content="新建" placement="top">
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="openNewFolderForm" ref="newFolderBtnRef">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4.5A1.5 1.5 0 013.5 3h3l1.5 2h4A1.5 1.5 0 0113.5 6.5v5A1.5 1.5 0 0112 13H4a1.5 1.5 0 01-1.5-1.5z"/><path d="M8 8v3M6.5 9.5h3"/></svg>
          </el-button>
        </el-tooltip>
        <el-tooltip content="管理" placement="top">
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="openEditFoldersDialog">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 12l-1 1 1 1 1-1M12 4l1-1-1-1-1 1M4 12l8-8"/><rect x="2" y="2" width="12" height="12" rx="1"/></svg>
          </el-button>
        </el-tooltip>
        <el-tooltip content="排序" placement="top">
          <el-dropdown trigger="click" @command="handleFolderSort" style="display:inline-flex">
            <el-button size="small" text style="padding:2px 4px;min-width:auto">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 3.5A1.5 1.5 0 013.5 2h3l1.5 2h4A1.5 1.5 0 0113.5 5.5v5A1.5 1.5 0 0112 12H4a1.5 1.5 0 01-1.5-1.5z"/><path d="M9 4.5l1.5 1.5L9 7.5M9 11.5l1.5-1.5L9 8.5"/></svg>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="custom-asc">默认排序</el-dropdown-item>
                <el-dropdown-item command="alpha-asc" divided>字母正序</el-dropdown-item>
                <el-dropdown-item command="alpha-desc">字母倒序</el-dropdown-item>
                <el-dropdown-item command="count-asc">文献数正序</el-dropdown-item>
                <el-dropdown-item command="count-desc">文献数倒序</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-tooltip>
        <div class="header-search" ref="folderSearchWrapRef">
          <el-tooltip content="搜索" placement="top">
            <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="toggleFolderSearch">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="6.5" cy="6.5" r="4"/><path d="M9.5 9.5L13 13"/></svg>
            </el-button>
          </el-tooltip>
          <el-input v-if="showFolderSearch" v-model="folderSearchKeyword" placeholder="搜文件夹…" size="small" clearable class="search-input" ref="folderSearchRef" />
        </div>
      </div>

      <!-- 新建文件夹表单 -->
      <div v-if="showNewFolderForm" class="inline-form" ref="newFolderFormRef">
        <div class="inline-form-fields">
          <el-tree-select v-model="newFolderParentId" :data="folderTreeWithRoot" :props="treeProps"
            check-strictly node-key="id" placeholder="父文件夹" clearable size="small" style="flex:1;min-width:0" />
          <el-input v-model="newFolderName" placeholder="文件夹名" size="small" style="flex:1;min-width:0" @keyup.enter="createFolder" />
        </div>
        <div class="inline-form-actions">
          <el-button size="small" type="primary" @click="createFolder">创建</el-button>
          <el-button size="small" @click="showNewFolderForm = false">取消</el-button>
        </div>
      </div>

      <div class="folder-all" :class="{ active: currentFolder === null }" @click="clearFolderFilter">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4.5A1.5 1.5 0 013.5 3h3l1.5 2h4A1.5 1.5 0 0113.5 6.5v5A1.5 1.5 0 0112 13H4a1.5 1.5 0 01-1.5-1.5z"/></svg>
        <span>我的文库</span>
      </div>

      <div class="virtual-folders">
        <div class="folder-all sub" :class="{ active: currentFolder === 'uncategorized' }" @click="setVirtualFolder('uncategorized')">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4.5A1.5 1.5 0 013.5 3h3l1.5 2h4A1.5 1.5 0 0113.5 6.5v5A1.5 1.5 0 0112 13H4a1.5 1.5 0 01-1.5-1.5z"/><path d="M5 8h6"/></svg>
          <span>未分类</span>
        </div>
        <div class="folder-all sub" :class="{ active: currentFolder === 'recent' }" @click="setVirtualFolder('recent')">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="8" cy="8" r="6"/><path d="M8 4v4l3 2"/></svg>
          <span>最近新增</span>
        </div>
      </div>

      <el-tree ref="treeRef" :data="sortedFolders" :props="treeProps" node-key="id"
        highlight-current :current-node-key="selectedFolderId" @node-click="onFolderClick" class="folder-tree"
        :filter-node-method="filterFolderNode">
        <template #default="{ data }">
          <span class="tree-node-label" :class="{
            'path-0': pathKeys.get(data.id) === 0,
            'path-1': pathKeys.get(data.id) === 1,
            'path-2': pathKeys.get(data.id) === 2,
          }">
            <span class="folder-name">{{ data.name }}</span>
            <span class="folder-count">({{ data.paperCount ?? 0 }})</span>
          </span>
        </template>
      </el-tree>

      <!-- 筛选（底部） -->
      <div class="filter-section">
        <h4>筛选</h4>
        <div class="filter-group">
          <span class="filter-label">阅读状态</span>
          <el-select v-model="filterStatus" placeholder="全部" clearable size="small" class="w-full" @change="loadPapers">
            <el-option label="全部" value="" />
            <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </div>
        <div class="filter-group">
          <span class="filter-label">标签</span>
          <el-select v-model="filterTag" placeholder="全部" clearable size="small" class="w-full" @change="loadPapers">
            <el-option label="全部" value="" />
            <el-option v-for="t in allTags" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </div>
      </div>
    </div>

    <!-- 分割线 -->
    <div v-if="sidebarVisible" class="divider" :class="{ active: resizing === 'left' }" @mousedown="startResize($event, 'left')"><div class="divider-handle"></div></div>

    <!-- ==================== 中栏 ==================== -->
    <div class="center-panel">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-tooltip :content="sidebarVisible ? '收起侧栏' : '展开侧栏'" placement="top">
            <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="sidebarVisible = !sidebarVisible">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2" y="2" width="4" height="12" rx="1"/><rect x="6" y="2" width="8" height="12" rx="1"/></svg>
            </el-button>
          </el-tooltip>
          <span class="toolbar-divider"></span>
          <el-tooltip content="导入论文" placement="top">
            <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="openImportDialog">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 10v2.5A1.5 1.5 0 003.5 14h9a1.5 1.5 0 001.5-1.5V10M8 2v9M5 8l3 3 3-3"/></svg>
            </el-button>
          </el-tooltip>
          <el-dropdown trigger="click" @command="handleExport">
            <el-button size="small" text style="padding:2px 4px;min-width:auto">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 8h12M8 2v12"/></svg>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="bibtex-single" :disabled="!currentPaper">导出当前 BibTeX</el-dropdown-item>
                <el-dropdown-item command="bibtex-batch" :disabled="!selectedPaperIds.length">导出选中 BibTeX</el-dropdown-item>
                <el-dropdown-item command="obsidian" :disabled="!selectedPaperIds.length">同步到 Obsidian</el-dropdown-item>
                <el-dropdown-item command="zotero" :disabled="!selectedPaperIds.length">同步到 Zotero</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <!-- 批量操作 -->
          <LibraryBatchSelectionBar
            :count="selectedPaperIds.length"
            @move="openBatchMoveDialog"
            @delete="confirmBatchDelete"
            @clear="selectedPaperIds = []"
          />
        </div>
        <div class="toolbar-right">
          <el-input v-model="paperSearchKeyword" placeholder="搜索论文…" size="small" clearable class="toolbar-search" @input="onPaperSearch" />
        </div>
      </div>

      <!-- 表格 -->
      <div class="table-wrapper">
        <PaperTable
          :papers="papers"
          :loading="tableLoading"
          :total="pagination.total"
          :page="pagination.page"
          :size="pagination.size"
          :selected-ids="selectedPaperIds"
          :current-paper-id="currentPaper?.id"
          :sort-by="sortBy"
          :sort-dir="sortDir"
          @page-change="onTablePageChange"
          @sort-change="onTableSortChange"
          @selection-change="selectedPaperIds = $event"
          @tag-click="openTagDialog"
          @status-change="(paper, status) => setPaperStatus(paper, status)"
          @analyze="goToAnalysis"
          @info="showPaperInfo"
          @action="(cmd, paper) => handlePaperAction(cmd, paper)"
          @open-pdf="openPaperPdf"
        />
      </div>
    </div>

    <!-- 分割线 -->
    <div v-if="currentPaper" class="divider" :class="{ active: resizing === 'right' }" @mousedown="startResize($event, 'right')"><div class="divider-handle"></div></div>

    <!-- ==================== 右栏 ==================== -->
    <div class="right-panel" v-if="currentPaper" :style="{ width: rightWidth + 'px' }">
      <div class="detail-header">
        <div class="detail-title-row">
          <el-input v-if="editingTitle" v-model="editTitleText" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }"
            class="title-input" @blur="saveTitle" @keydown.enter.prevent="saveTitle" ref="titleInputRef" />
          <h2 v-else class="detail-title" @click="startEditTitle">{{ currentPaper.title }}</h2>
        </div>
        <el-tooltip content="收起详情" placement="top">
          <el-button size="small" text style="padding:2px 4px;min-width:auto" @click="currentPaper = null">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="10" y="2" width="4" height="12" rx="1"/><rect x="2" y="2" width="8" height="12" rx="1"/></svg>
          </el-button>
        </el-tooltip>
      </div>
      <!-- AI 分析触发/状态/取消/重试入口 -->
      <div class="detail-ai-status">
        <el-button
          size="small"
          type="primary"
          :disabled="!aiLoading && currentPaper.processingStatus === 'COMPLETED'"
          @click="aiLoading ? cancelAiWithHint() : triggerAiAnalysis()"
        >
          {{ aiLoading ? '取消分析' : 'AI 分析' }}
        </el-button>
        <el-tag v-if="!aiLoading && currentPaper.processingStatus" :type="statusTagType" size="small" style="margin-left:8px">
          {{ currentPaper.processingStatus === 'COMPLETED' ? '已完成' : processingLabel(currentPaper.processingStatus) }}
        </el-tag>
        <span v-if="aiLoading" class="stage-text" style="margin-left:8px">{{ aiStatus }}</span>
        <span v-else-if="aiError" class="error-text" style="margin-left:8px">{{ aiError.message }}
          <el-button size="small" link type="primary" @click="retryAi()">重试</el-button>
        </span>
      </div>
      <div class="detail-divider"></div>
      <div class="detail-item"><span class="label">作者</span>{{ formatAuthors(currentPaper.authors) }}</div>
      <div class="detail-item"><span class="label">年份</span>{{ currentPaper.year }}</div>
      <div class="detail-item"><span class="label">来源</span>{{ currentPaper.source }}</div>
      <div class="detail-item"><span class="label">DOI</span>{{ currentPaper.doi }}</div>
      <div class="detail-item"><span class="label">摘要</span>{{ currentPaper.abstractText || '暂无摘要' }}</div>
      <!-- AI 分析结果 -->
      <div class="detail-item" v-if="paperAnalysis && paperAnalysis.coreContribution">
        <span class="label">AI · 核心贡献</span>
        <p class="extracted-text">{{ paperAnalysis.coreContribution }}</p>
      </div>
      <div class="detail-item" v-if="paperAnalysis && paperAnalysis.methodSummary">
        <span class="label">AI · 方法概述</span>
        <p class="extracted-text">{{ paperAnalysis.methodSummary }}</p>
      </div>
      <div class="detail-item"><span class="label">关键词</span>{{ currentPaper.keywords }}</div>
      <div class="detail-item"><span class="label">获取方式</span>{{ acquisitionLabel(currentPaper.acquisitionMethod) }}</div>
      <div class="detail-item"><span class="label">arXiv ID</span>{{ currentPaper.arxivId || '--' }}</div>
      <div class="detail-item">
        <span class="label">来源链接</span>
        <a v-if="currentPaper.sourceUrl" :href="currentPaper.sourceUrl" target="_blank" style="word-break:break-all">{{ currentPaper.sourceUrl }}</a>
        <span v-else>--</span>
      </div>
      <div class="detail-item">
        <span class="label">PDF</span>
        <a v-if="currentPaper.pdfPath" @click.prevent="openCurrentPaperPdf" href="#">打开 PDF</a>
        <span v-else>暂无</span>
      </div>
      <div class="detail-item" v-if="currentPaper.pdfPath">
        <span class="label">阅读时间</span>
        <span>{{ formatReadDuration(currentPaper.readSeconds || 0) }}</span>
      </div>
      <div class="detail-item">
        <span class="label">阅读状态</span>
        <el-select v-model="currentPaper.readingStatus" size="small" @change="setPaperStatus(currentPaper, $event)" style="flex:1">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>
      <div class="detail-item">
        <span class="label">标签</span>
        <el-select
          v-model="currentPaperTagIds"
          placeholder="选择或输入新标签"
          multiple
          filterable
          allow-create
          collapse-tags
          collapse-tags-tooltip
          size="small"
          style="flex:1"
          @change="savePaperTags"
        >
          <el-option v-for="t in allTags" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
      </div>

      <!-- 推荐 -->
      <div class="detail-item" v-if="currentPaper">
        <span class="label">推荐</span>
        <div class="rec-block" style="flex:1">
          <div v-if="currentImportRec?.loading" class="stage-text">{{ currentImportRec.status || '处理中…' }}</div>
          <div v-else-if="currentImportRec?.error && !currentImportRec?.result" class="error-text">{{ currentImportRec.error }}</div>
          <template v-else>
            <div class="rec-row">
              <span>元数据</span>
              <el-button v-if="currentImportRec?.result?.metadata" size="small" text type="primary" @click="openMetadataRecommendationDialog">查看</el-button>
              <el-button v-else size="small" text type="primary" @click="recommendDetailMetadata">分析</el-button>
            </div>
            <div class="rec-row">
              <span>标签</span>
              <el-button v-if="currentImportRec?.result?.tags?.length" size="small" text type="primary" @click="openTagRecommendationDialog">选择</el-button>
              <el-button v-else size="small" text type="primary" @click="recommendDetailTags">推荐</el-button>
            </div>
            <div class="rec-row">
              <span>文件夹</span>
              <el-button v-if="currentImportRec?.result?.folder" size="small" text type="primary" @click="openFolderRecommendationDialog">查看</el-button>
              <el-button v-else size="small" text type="primary" @click="recommendDetailFolder">推荐</el-button>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 元数据识别预览：分析与应用分开，避免未确认就改写论文信息。 -->
    <el-dialog v-model="metadataRecommendDialogVisible" title="元数据识别结果" width="560px">
      <p v-if="metadataRecommendation?.message" class="recommend-dialog-tip">
        {{ metadataRecommendation.message }}
      </p>
      <div v-if="metadataRecommendation" class="metadata-preview-grid">
        <div v-for="field in metadataPreviewFields" :key="field.key" class="metadata-preview-row">
          <span class="metadata-preview-label">{{ field.label }}</span>
          <span class="metadata-preview-value" :class="{ empty: !metadataRecommendation[field.key] }">
            {{ formatMetadataValue(field.key, metadataRecommendation[field.key]) || '未识别到' }}
          </span>
        </div>
      </div>
      <p v-else class="recommend-dialog-tip">暂无识别结果</p>
      <template #footer>
        <el-button @click="metadataRecommendDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!hasMetadataRecommendation" @click="confirmRecommendedMetadata">应用</el-button>
      </template>
    </el-dialog>

    <!-- 导入前的新文件夹建议：只在用户确认后创建，避免 Agent 直接改动文件夹树。 -->
    <el-dialog v-model="importFolderRecommendDialogVisible" title="推荐文件夹" width="460px">
      <p class="recommend-dialog-tip">{{ importFolderRecommendation?.reason || '没有合适的现有文件夹，建议新建子文件夹' }}</p>
      <el-form label-width="80px">
        <el-form-item label="父文件夹">
          <el-tree-select v-model="importNewFolderParentId" :data="folderTreeWithRoot" :props="treeProps"
            node-key="id" check-strictly clearable placeholder="我的文库" style="width:100%" />
        </el-form-item>
        <el-form-item label="新文件夹">
          <el-input v-model="importNewFolderName" placeholder="例如：AAoI" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importFolderRecommendDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importFolderSaving" @click="confirmImportFolderRecommendation">确认新建</el-button>
      </template>
    </el-dialog>

    <!-- 推荐标签 -->
    <el-dialog v-model="tagRecommendDialogVisible" title="推荐标签" width="420px">
      <p class="recommend-dialog-tip">请选择要应用的标签</p>
      <el-checkbox-group v-model="tagRecommendSelected" class="recommend-tag-list">
        <el-checkbox v-for="tag in (currentImportRec?.result?.tags || [])" :key="tag" :label="tag">
          {{ tag }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="tagRecommendDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmRecommendedTags">应用</el-button>
      </template>
    </el-dialog>

    <!-- 推荐文件夹 -->
    <el-dialog v-model="folderRecommendDialogVisible" title="推荐文件夹" width="460px">
      <template v-if="currentImportRec?.result?.folder?.recommended != null">
        <p>推荐文件夹：{{ folderPath(currentImportRec.result.folder.recommended) }}</p>
        <p v-if="currentPaper.folderId === currentImportRec.result.folder.recommended" class="folder-recommend-ok">当前文件夹已合适</p>
        <p v-else class="recommend-dialog-tip">确认后将论文移动到该文件夹。</p>
      </template>
      <template v-else-if="currentImportRec?.result?.folder?.suggestNew">
        <p class="recommend-dialog-tip">没有合适的现有文件夹，建议新建文件夹：</p>
        <el-form label-width="80px">
          <el-form-item label="父文件夹">
            <el-tree-select v-model="recommendNewFolderParentId" :data="folderTreeWithRoot" :props="treeProps"
              node-key="id" check-strictly clearable placeholder="我的文库" style="width:100%" />
          </el-form-item>
          <el-form-item label="新文件夹">
            <el-input v-model="recommendNewFolderName" placeholder="例如：B 方法" />
          </el-form-item>
        </el-form>
      </template>
      <span v-else class="recommend-dialog-tip">暂无合适的文件夹建议</span>
      <template #footer>
        <el-button @click="folderRecommendDialogVisible = false">取消</el-button>
        <el-button
          v-if="currentImportRec?.result?.folder?.recommended != null && currentPaper.folderId !== currentImportRec.result.folder.recommended"
          type="primary" @click="confirmFolderRecommendation">确认移动</el-button>
        <el-button v-else-if="currentImportRec?.result?.folder?.suggestNew" type="primary" :loading="recommendFolderSaving" @click="confirmFolderRecommendation">新建并归档</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 编辑文件夹弹窗 ==================== -->
    <el-dialog v-model="showEditFoldersDialog" title="编辑文件夹" width="500px" destroy-on-close>
      <p style="font-size:12px;color:#909399;margin:0 0 8px">点击选中文件夹，操作后点「确认」保存</p>
      <div style="margin-bottom:8px;display:flex;gap:4px;flex-wrap:wrap">
        <el-button size="small" @click="editMoveUp" :disabled="!editCanMoveUp">↑ 上移</el-button>
        <el-button size="small" @click="editMoveDown" :disabled="!editCanMoveDown">↓ 下移</el-button>
        <el-button size="small" @click="editMoveOut" :disabled="!editFolderId || editSelectedParentId == null">↩ 移出</el-button>
        <el-button size="small" @click="startMoveIn" :disabled="!editFolderId">↪ 移入</el-button>
        <el-button size="small" type="danger" @click="editDelete" :disabled="!editFolderId">删除</el-button>
      </div>
      <div v-if="moveInSource" style="margin-bottom:4px;padding:4px 8px;background:#ecf5ff;border-radius:3px;font-size:12px">
        将「{{ moveInSource.name }}」移入到 → 点击目标文件夹 | <el-button size="small" text @click="moveInSource=null">取消</el-button>
      </div>
      <el-tree ref="editTreeRef" :data="editFolders" :props="treeProps" node-key="id"
        :default-expanded-keys="editExpandedKeys"
        highlight-current :current-node-key="editFolderId"
        @node-click="onEditTreeClick"
        @node-expand="onEditTreeNodeExpand"
        @node-collapse="onEditTreeNodeCollapse"
        style="max-height:300px;overflow-y:auto"
      />
      <div style="margin-top:8px;display:flex;gap:4px">
        <el-input v-model="editFolderName" placeholder="重命名" size="small" style="flex:1" />
        <el-button size="small" @click="editRename" :disabled="!editFolderId||!editFolderName.trim()">重命名</el-button>
      </div>
      <template #footer>
        <el-button @click="cancelEditFolders">取消</el-button>
        <el-button type="primary" @click="confirmEditFolders">确认</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 批量移动对话框 ==================== -->
    <el-dialog v-model="batchMoveDialogVisible" title="批量移动论文" width="420px">
      <p style="font-size:13px;color:var(--ra-text-secondary);margin:0 0 12px">已选 {{ selectedPaperIds.length }} 篇论文</p>
      <el-form label-width="96px">
        <el-form-item label="目标文件夹">
          <el-tree-select v-model="batchMoveFolderId" :data="folders" :props="treeProps"
            check-strictly node-key="id" placeholder="暂不分类（根目录）" clearable class="w-full" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchMoveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="doBatchMove" :loading="batchMoving">确认移动</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 标签编辑对话框 ==================== -->
    <el-dialog v-model="tagDialogVisible" :title="tagDialogPaper?.title || '编辑标签'" width="420px"
      :close-on-click-modal="false" @closed="tagDialogPaper = null">
      <p v-if="tagDialogPaper" style="font-size:12px;color:#909399;margin:0 0 10px">为论文选择或输入新标签</p>
      <el-select
        v-if="tagDialogPaper"
        v-model="tagDialogSelectedIds"
        placeholder="选择或输入新标签"
        multiple
        filterable
        allow-create
        collapse-tags
        collapse-tags-tooltip
        size="small"
        style="width:100%"
      >
        <el-option v-for="t in allTags" :key="t.id" :label="t.name" :value="t.id">
          <div class="tag-option-row">
            <span>{{ t.name }}</span>
            <span class="tag-delete-btn" @click.stop="deleteTag(t)" title="删除标签">✕</span>
          </div>
        </el-option>
      </el-select>
      <template #footer>
        <el-button @click="tagDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveTagDialog">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 导入对话框 ==================== -->
    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑论文' : '导入论文'" width="520px">
      <!-- 新建模式：PDF 上传 + DOI 自动获取 -->
      <template v-if="!isEditing">
        <div class="upload-zone" @click="$refs.uploadInputRef.click()" @dragover.prevent @drop.prevent="onDropFile">
          <svg width="28" height="28" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2"><path d="M4 2v12l4-3 4 3V2a1 1 0 00-1-1H5a1 1 0 00-1 1z"/><path d="M6 10V4h4v3H7v3"/></svg>
          <div v-if="!uploadFile" class="upload-text">拖拽 PDF 到此处或 <em>点击上传</em></div>
          <div v-else class="upload-file">{{ uploadFile.name }} <span class="upload-remove" @click.stop="uploadFile=null">✕</span></div>
        </div>
        <input type="file" ref="uploadInputRef" accept=".pdf" @change="onFileChange" style="display:none" />
        <div class="doi-row">
          <span class="doi-or">— 或 —</span>
          <div class="doi-input-wrap">
            <el-input v-model="doiInput" placeholder="如 10.1038/nature14539" size="small" @keyup.enter="fetchDoi" clearable />
            <el-button size="small" type="primary" @click="fetchDoi" :loading="fetchingDoi">获取</el-button>
            <el-tooltip content="从 PDF 识别 DOI 或 arXiv ID，并从对应服务获取论文元数据" placement="top">
              <span>
                <el-button size="small" :type="enriching ? 'info' : 'success'" @click="autoIdentify" :loading="enriching" :disabled="!uploadFile">
                  {{ enriching ? '识别中…' : '自动填充元数据' }}
                </el-button>
              </span>
            </el-tooltip>
          </div>
        </div>
        <div v-if="!isEditing && form.doi && form.sourceUrl" class="doi-source-hint">
          <a :href="form.sourceUrl" target="_blank" rel="noopener">打开来源页</a>
          <a v-if="doiPdfUrl" :href="doiPdfUrl" target="_blank" rel="noopener">打开公开 PDF</a>
          <span v-else>PDF 需要手动上传，出版社权限可能限制下载</span>
        </div>
        <div class="import-preview" v-if="form.title || uploadFile">
          <div class="preview-title">识别结果</div>
          <p class="import-helper">自动填充会从 PDF 识别 DOI/arXiv ID，再获取标题、作者、年份、来源和摘要；摘要最多填充 3000 字，也可以继续手动修改。</p>
          <el-form label-width="70px" size="small">
            <el-form-item label="标题"><el-input v-model="form.title" placeholder="自动填充或手动输入论文标题" /></el-form-item>
            <el-form-item label="作者"><el-input v-model="form.authors" placeholder="自动识别或手动输入" /></el-form-item>
            <el-form-item label="年份"><el-input-number v-model="form.year" :min="1900" :max="2030" style="width:120px" /></el-form-item>
            <el-form-item label="来源"><el-input v-model="form.source" /></el-form-item>
            <el-form-item label="DOI"><el-input v-model="form.doi" placeholder="自动识别或手动输入" /></el-form-item>
            <el-form-item label="arXiv ID"><el-input v-model="form.arxivId" placeholder="自动识别或手动输入" /></el-form-item>
            <el-form-item label="摘要"><el-input v-model="form.abstractText" type="textarea" :rows="3" placeholder="自动识别或手动输入" /></el-form-item>
            <el-form-item label="文件夹">
              <div style="display:flex;gap:6px;width:100%">
                <el-tree-select v-model="form.folderId" :data="folders" :props="treeProps"
                  check-strictly node-key="id" placeholder="选择文件夹" clearable style="flex:1" />
                <el-button size="small" text type="primary" @click="recommendFolder" :loading="recommending"
                  :disabled="!form.title">Agent 推荐</el-button>
              </div>
            </el-form-item>
          </el-form>
        </div>
      </template>
      <!-- 编辑模式：完整表单 -->
      <el-form v-else :model="form" label-width="80px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="作者"><el-input v-model="form.authors" /></el-form-item>
        <el-form-item label="年份"><el-input-number v-model="form.year" :min="1900" :max="2030" /></el-form-item>
        <el-form-item label="来源"><el-input v-model="form.source" /></el-form-item>
        <el-form-item label="DOI"><el-input v-model="form.doi" /></el-form-item>
        <el-form-item label="arXiv ID"><el-input v-model="form.arxivId" /></el-form-item>
        <el-form-item label="摘要"><el-input v-model="form.abstractText" type="textarea" rows="3" /></el-form-item>
        <el-form-item label="关键词"><el-input v-model="form.keywords" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="文件夹">
          <el-tree-select v-model="form.folderId" :data="folders" :props="treeProps"
            check-strictly node-key="id" placeholder="选择文件夹" clearable class="w-full" />
        </el-form-item>
        <el-form-item label="阅读状态">
          <el-select v-model="form.readingStatus" class="w-full">
            <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPaper" :disabled="!isEditing && !form.title">
          {{ isEditing ? '保存' : '导入论文' }}
        </el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, onActivated, onDeactivated, defineAsyncComponent, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { waitForAnalysis } from '@/utils/analysis.js'
import { useGlobalTask } from '@/composables/useGlobalTask.js'
import { usePaperImportRecommendations } from '@/composables/usePaperImportRecommendations.js'
import LibraryBatchSelectionBar from '@/components/library/LibraryBatchSelectionBar.vue'
import { exportSingleBibTeX, exportBatchBibTeX, syncObsidian, syncZotero, downloadBlob } from '@/api/export'
import { researchRouteLocation } from '@/router/workbenchRoute.js'

defineOptions({ name: 'LibraryView' })

const router = useRouter()
import { ElMessage, ElMessageBox } from 'element-plus'

// 大型表格只在资料库页面真正使用时加载，避免进入首页就下载大体积依赖。
const PaperTable = defineAsyncComponent(() => import('@/components/PaperTable.vue'))

const treeRef = ref(null)
const pathKeys = ref(new Map())
const selectedFolderId = ref(null)
const expandedId = ref(null)
const folders = ref([])
const papers = ref([])
const currentPaper = ref(null)
const currentFolder = ref(null)
const folderSearchKeyword = ref('')
const filterStatus = ref('')
const filterTag = ref('')
const allTags = ref([])
const paperSearchKeyword = ref('')
const showFolderSearch = ref(false)
const tableLoading = ref(false)
const folderSearchRef = ref(null)
const folderSearchWrapRef = ref(null)
const newFolderBtnRef = ref(null)
const newFolderFormRef = ref(null)
const pagination = ref({ page: 1, size: 20, total: 0 })
const showNewFolderForm = ref(false)
const newFolderName = ref('')
const newFolderParentId = ref(null)
const dialogVisible = ref(false)
const isEditing = ref(false)
const editPaperId = ref(null)
const form = ref(makeEmptyForm())
const sidebarVisible = ref(true)
const sortBy = ref('created_at')
const sortDir = ref('DESC')
const folderSortMode = ref('custom')
const folderSortDir = ref('ASC')
const leftWidth = ref(240)
const rightWidth = ref(300)
const editingTitle = ref(false)
const editTitleText = ref('')
const titleInputRef = ref(null)
const resizing = ref(null)
const uploadFile = ref(null)
const doiInput = ref('')
const doiPdfUrl = ref('')
const fetchingDoi = ref(false)
const enriching = ref(false)

// ===== 全局后台任务：论文库 AI 分析切换页面不取消 =====
const {
  isLoading: aiLoading,
  statusText: aiStatus,
  error: aiError,
  run: runAi,
  cancel: cancelAi,
  retry: retryAi
} = useGlobalTask('library-ai-analysis')

const recommending = ref(false)
const {
  recs: importRecs,
  watchImport,
  applyTags: applyRecTags,
  applyFolder: applyRecFolder,
  applyMetadata: applyRecMetadata,
  ensureRec
} = usePaperImportRecommendations()
const paperAnalysis = ref(null)
const currentPaperTagIds = ref([])
const selectedPaperIds = ref([])
const batchMoveDialogVisible = ref(false)
const batchMoveFolderId = ref(null)
const batchMoving = ref(false)

// 标签编辑对话框
const tagDialogVisible = ref(false)
const tagDialogPaper = ref(null)
const tagDialogSelectedIds = ref([])
const tagRecommendDialogVisible = ref(false)
const tagRecommendSelected = ref([])
const metadataRecommendDialogVisible = ref(false)
const metadataRecommendation = ref(null)
const folderRecommendDialogVisible = ref(false)
const recommendNewFolderName = ref('')
const recommendNewFolderParentId = ref(null)
const recommendFolderSaving = ref(false)
const importFolderRecommendDialogVisible = ref(false)
const importFolderRecommendation = ref(null)
const importNewFolderName = ref('')
const importNewFolderParentId = ref(null)
const importFolderSaving = ref(false)

const statusTagType = computed(() => {
  const s = currentPaper.value?.processingStatus
  if (s === 'COMPLETED') return 'success'
  if (s === 'PROCESSING') return 'warning'
  if (s === 'FAILED') return 'danger'
  return 'info'
})

const currentImportRec = computed(() => {
  return currentPaper.value ? importRecs.get(currentPaper.value.id) : null
})

const metadataPreviewFields = [
  { key: 'title', label: '标题' },
  { key: 'authors', label: '作者' },
  { key: 'year', label: '年份' },
  { key: 'source', label: '来源' },
  { key: 'doi', label: 'DOI' },
  { key: 'abstractText', label: '摘要' },
  { key: 'keywords', label: '关键词' }
]

const hasMetadataRecommendation = computed(() => {
  const metadata = metadataRecommendation.value
  return !!metadata && metadataPreviewFields.some(field => metadata[field.key] != null && metadata[field.key] !== '')
})

const treeProps = { children: 'children', label: 'name' }
const statusOptions = [
  { label: '未读', value: 'UNREAD', type: 'info' },
  { label: '正读', value: 'READING', type: 'danger' },
  { label: '已读', value: 'READ', type: 'success' }
]

// 新建/移动文件夹时，在真实文件夹树顶部附加「我的文库」虚拟根节点
const folderTreeWithRoot = computed(() => [{
  id: null,
  name: '我的文库',
  children: folders.value
}])

function folderName(id) {
  const all = flattenTree(folders.value)
  return all.find(f => f.id === id)?.name || id
}

function folderPath(id) {
  if (id == null) return '我的文库'
  const all = flattenTree(folders.value)
  const byId = new Map(all.map(folder => [folder.id, folder]))
  const parts = []
  const visited = new Set()
  let current = byId.get(Number(id)) || byId.get(id)
  while (current && !visited.has(current.id)) {
    visited.add(current.id)
    if (current.name) parts.unshift(current.name)
    current = byId.get(current.parentId)
  }
  return parts.join(' / ') || folderName(id)
}

function findFolderIdByPath(path) {
  if (!path) return null
  const target = path.split('/').map(part => part.trim()).filter(Boolean).join(' / ')
  return flattenTree(folders.value).find(folder => folderPath(folder.id) === target)?.id ?? null
}

function makeEmptyForm() {
  return { title: '', authors: '', year: null, source: '', doi: '',
    arxivId: '', sourceUrl: '',
    abstractText: '', keywords: '', folderId: null, readingStatus: 'UNREAD' }
}

const sortedFolders = computed(() => {
  const arr = JSON.parse(JSON.stringify(folders.value))
  sortFolders(arr)
  return arr
})

function sortFolders(nodes) {
  if (folderSortMode.value === 'custom') {
    nodes.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  } else {
    nodes.sort((a, b) => {
      let v = folderSortMode.value === 'alpha'
        ? (a.name || '').localeCompare(b.name || '')
        : (a.paperCount || 0) - (b.paperCount || 0)
      return folderSortDir.value === 'ASC' ? -v : v
    })
  }
  for (const n of nodes) { if (n.children?.length) sortFolders(n.children) }
}

function formatAuthors(json) {
  try {
    const parsed = typeof json === 'string' ? JSON.parse(json) : json
    if (Array.isArray(parsed)) return parsed.map(a => (a?.name || '') + (a?.role === 'corresponding' ? '*' : '')).filter(Boolean).join(', ')
    if (parsed && typeof parsed === 'object' && parsed.name) return parsed.name
    return typeof json === 'string' ? json : ''
  } catch { return json || '' }
}
function formatMetadataValue(key, value) {
  if (key === 'authors') return formatAuthors(value)
  return typeof value === 'string' ? decodeHtmlEntities(value) : value
}
function formatDate(d) { return d?.substring(0,7)||'' }
function acquisitionLabel(v) { const m={OA:'开放获取',BROWSER_DOWNLOAD:'浏览器下载',MANUAL_UPLOAD:'手动上传'}; return m[v]||v||'--' }
function processingLabel(v) { const m={PENDING:'等待处理',PROCESSING:'处理中',COMPLETED:'已完成',FAILED:'失败'}; return m[v]||v }
function statusLabel(v) { return statusOptions.find(o => o.value === v)?.label || v || '--' }
function statusType(v) { return statusOptions.find(o => o.value === v)?.type || 'info' }
function categoryLabel(paper) {
  if (paper.arxivId) return '预印本'
  if (paper.source) {
    const s = paper.source.toLowerCase()
    if (s.includes('journal') || s.includes('letters') || s.includes('transactions') || s.includes('magazine')) return '期刊'
    if (s.includes('conference') || s.includes('proceedings') || s.includes('symposium') || s.includes('workshop')) return '会议'
    return '期刊/会议'
  }
  return '--'
}
function sortLabel(col) { return sortBy.value!==col?'↕':sortDir.value==='ASC'?'↑':'↓' }
function toggleSort(col) { sortDir.value=sortBy.value===col?(sortDir.value==='ASC'?'DESC':'ASC'):'ASC'; sortBy.value=col; loadPapers() }
function handleFolderSort(cmd) {
  const [mode, dir] = cmd.split('-')
  folderSortMode.value = mode
  folderSortDir.value = dir === 'asc' ? 'ASC' : 'DESC'
}

async function loadFolders() { const r=await api.get('/folders'); folders.value=r.data }
async function loadAllTags() { const r=await api.get('/tags'); allTags.value=r.data || [] }
async function loadPapers() {
  tableLoading.value = true
  try {
    const r = await api.get('/papers', { params: { folder: currentFolder.value, keyword: paperSearchKeyword.value || null, tag: filterTag.value || null, status: filterStatus.value || null, sortBy: sortBy.value, sortDir: sortDir.value, page: pagination.value.page, size: pagination.value.size } })
    const d = r.data; papers.value = d.records; pagination.value.total = d.total; pagination.value.page = d.current
  } finally {
    tableLoading.value = false
  }
}

function syncPaperInList(paper) {
  const index = papers.value.findIndex(row => row.id === paper?.id)
  if (index >= 0) {
    const merged = { ...papers.value[index], ...paper }
    papers.value = papers.value.map((row, rowIndex) => rowIndex === index ? merged : row)
  }
}
function onTablePageChange(page) { pagination.value.page = page; loadPapers() }
function onTableSortChange(by, dir) { sortBy.value = by; sortDir.value = dir; pagination.value.page = 1; loadPapers() }
async function selectPaper(id) {
  const r=await api.get(`/papers/${id}`)
  currentPaper.value=r.data
  currentPaperTagIds.value=(currentPaper.value.tags||[]).map(t=>t.id)
  loadAnalysis(id)
}
async function refreshCurrentPaper() {
  if (!currentPaper.value) return
  await selectPaper(currentPaper.value.id)
}
function formatReadDuration(totalSeconds) {
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}
async function savePaperTags() {
  if (!currentPaper.value) return
  await saveTagsForPaper(currentPaper.value.id, currentPaperTagIds.value)
  await selectPaper(currentPaper.value.id)
}

/**
 * 保存论文标签。
 * @param {number} paperId
 * @param {Array} selectedIds 标签 id 或新标签名称的混合数组
 */
async function saveTagsForPaper(paperId, selectedIds) {
  const existingIds = selectedIds
    .filter(v => typeof v === 'number' || /^\d+$/.test(v))
    .map(v => Number(v))
  const newNames = selectedIds
    .filter(v => !(typeof v === 'number' || /^\d+$/.test(v)))
    .map(v => String(v).trim())
    .filter(Boolean)

  const created = await Promise.all(newNames.map(name => api.post('/tags', { name }).then(r => r.data.id)))
  const tagIds = [...existingIds, ...created]
  await api.post(`/tags/papers/${paperId}/tags`, { tagIds })
  await Promise.all([loadPapers(), loadAllTags()])
}

function openTagDialog(paper) {
  tagDialogPaper.value = paper
  tagDialogSelectedIds.value = (paper.tags || []).map(t => t.id)
  tagDialogVisible.value = true
}

async function saveTagDialog() {
  if (!tagDialogPaper.value) return
  await saveTagsForPaper(tagDialogPaper.value.id, tagDialogSelectedIds.value)
  tagDialogVisible.value = false
  tagDialogPaper.value = null
}

/** 在标签下拉框中直接删除某个全局标签 */
async function deleteTag(tag) {
  try {
    await api.delete(`/tags/${tag.id}`)
    // 如果当前论文已选中该标签，从选中列表中移除
    tagDialogSelectedIds.value = tagDialogSelectedIds.value.filter(id => id !== tag.id)
    await Promise.all([loadPapers(), loadAllTags()])
    ElMessage.success('标签已删除')
  } catch (e) {
    ElMessage.error('删除标签失败：' + (e.response?.data?.message || e.message))
  }
}

async function setPaperStatus(paper, status) {
  const previousStatus = paper.readingStatus
  paper.readingStatus = status
  syncPaperInList(paper)
  if (currentPaper.value?.id === paper.id) {
    currentPaper.value.readingStatus = status
  }
  try {
    // 状态变更只提交必要字段，避免把 PDF 提取文本等大字段一并提交。
    const response = await api.put(`/papers/${paper.id}`, {
      title: paper.title,
      readingStatus: status
    })
    const saved = response?.data || response
    if (saved && typeof saved === 'object') {
      Object.assign(paper, saved)
      if (currentPaper.value?.id === paper.id) Object.assign(currentPaper.value, saved)
      syncPaperInList(paper)
    }
    ElMessage.success('状态已更新')
  } catch (e) {
    paper.readingStatus = previousStatus
    syncPaperInList(paper)
    if (currentPaper.value?.id === paper.id) currentPaper.value.readingStatus = previousStatus
    ElMessage.error('状态更新失败：' + (e.response?.data?.message || e.message))
  }
}

let paperSearchTimer=null
function onPaperSearch() { clearTimeout(paperSearchTimer); paperSearchTimer=setTimeout(()=>loadPapers(),300) }

/** 文件夹名过滤（客户端） */
function filterFolderNode(value, data) {
  if (!value) return true
  return (data.name || '').toLowerCase().includes(value.toLowerCase())
}
watch(folderSearchKeyword, v => treeRef.value?.filter(v))

function collectAllIds(nodes) { const ids=[]; for(const n of nodes){ids.push(n.id);if(n.children)ids.push(...collectAllIds(n.children))} return ids }
function collectFolderIds(node) { return collectAllIds([node]) }
function findPathToNode(tree,targetId,path=[]) {
  for(const n of tree){ const np=[...path,n.id]; if(n.id===targetId)return np; if(n.children?.length){const f=findPathToNode(n.children,targetId,np);if(f)return f} }
  return null
}

function onFolderClick(node) {
  currentFolder.value=collectFolderIds(node).join(','); selectedFolderId.value=node.id
  const path=findPathToNode(folders.value,node.id)||[]; const map=new Map(); path.forEach((id,i)=>map.set(id,i)); pathKeys.value=map
  if(expandedId.value===node.id){treeRef.value?.getNode(node.id)?.collapse();expandedId.value=null}
  else{if(expandedId.value)treeRef.value?.getNode(expandedId.value)?.collapse();for(const id of path)treeRef.value?.getNode(id)?.expand();expandedId.value=node.id}
  loadPapers()
}
function setVirtualFolder(type) {
  currentFolder.value=type;selectedFolderId.value=null;expandedId.value=null;pathKeys.value=new Map()
  collectAllIds(folders.value).forEach(id=>treeRef.value?.getNode(id)?.collapse());loadPapers()
}
function clearFolderFilter() {
  currentFolder.value=null;selectedFolderId.value=null;expandedId.value=null;pathKeys.value=new Map()
  collectAllIds(folders.value).forEach(id=>treeRef.value?.getNode(id)?.collapse());loadPapers()
}
// ===== 编辑文件夹弹窗（本地操作 + 确认批量提交） =====
const showEditFoldersDialog = ref(false)
const editFolderId = ref(null)
const editFolderName = ref('')
const editFolders = ref([]) // 本地副本
const editTreeRef = ref(null)
const editExpandedKeys = ref([])
const moveInSource = ref(null)
const editOps = ref([]) // 待提交操作队列: [{type, id, body}]

function openEditFoldersDialog() {
  editFolderId.value = null; editFolderName.value = ''; moveInSource.value = null
  editFolders.value = JSON.parse(JSON.stringify(folders.value))
  // 初次打开时展开已有父目录；之后的删除/移动不重建树，用户当前展开状态会保持。
  editExpandedKeys.value = collectExpandableFolderIds(editFolders.value)
  editOps.value = []
  showEditFoldersDialog.value = true
}

function cancelEditFolders() { showEditFoldersDialog.value = false }

function collectExpandableFolderIds(nodes, result = []) {
  for (const node of nodes || []) {
    if (node.children?.length) {
      result.push(node.id)
      collectExpandableFolderIds(node.children, result)
    }
  }
  return result
}

function onEditTreeNodeExpand(node) {
  if (!editExpandedKeys.value.includes(node.id)) editExpandedKeys.value.push(node.id)
}

function onEditTreeNodeCollapse(node) {
  editExpandedKeys.value = editExpandedKeys.value.filter(id => id !== node.id)
}

function expandEditedFolder(id) {
  nextTick(() => {
    editTreeRef.value?.getNode(id)?.expand()
    onEditTreeNodeExpand({ id })
  })
}

// 记录操作到队列
function addEditOp(type, id, body) { editOps.value.push({ type, id, body }) }

async function confirmEditFolders() {
  try {
    for (const op of editOps.value) {
      if (op.type === 'move') await api.put(`/folders/${op.id}/move`, op.body)
      else if (op.type === 'rename') await api.put(`/folders/${op.id}`, op.body)
      else if (op.type === 'delete') await api.delete(`/folders/${op.id}`)
    }
    showEditFoldersDialog.value = false
    await loadFolders()
  } catch (e) {
    alert('操作失败：' + (e.response?.data?.message || e.message))
  }
}

const editSelectedParentId = computed(() => {
  if (!editFolderId.value) return undefined
  const f = findFolderById(editFolders.value, editFolderId.value)
  return f?.parentId ?? null
})

const editCanMoveUp = computed(() => {
  if (!editFolderId.value) return false
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return false
  const all = flattenTree(editFolders.value)
  const siblings = all.filter(f => (f.parentId ?? null) === (folder.parentId ?? null))
  siblings.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  return siblings.findIndex(f => f.id === folder.id) > 0
})

const editCanMoveDown = computed(() => {
  if (!editFolderId.value) return false
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return false
  const all = flattenTree(editFolders.value)
  const siblings = all.filter(f => (f.parentId ?? null) === (folder.parentId ?? null))
  siblings.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  const idx = siblings.findIndex(f => f.id === folder.id)
  return idx >= 0 && idx < siblings.length - 1
})

function onEditTreeClick(node) {
  if (moveInSource.value) {
    if (node.id === moveInSource.value.id) return
    moveFolderToLocal(node.id)
    return
  }
  editFolderId.value = node.id
}

// === 本地修改 editFolders，不调 API ===
function findFolderById(nodes, id) {
  for (const n of nodes) { if (n.id === id) return n; if (n.children) { const f = findFolderById(n.children, id); if (f) return f } }
  return null
}
function flattenTree(nodes, result = []) {
  for (const n of nodes) { result.push(n); if (n.children) flattenTree(n.children, result) }
  return result
}
// 从树中移除节点
function removeFromTree(nodes, id) {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].id === id) { nodes.splice(i, 1); return true }
    if (nodes[i].children && removeFromTree(nodes[i].children, id)) return true
  }
  return false
}
// 把节点加到目标父节点下
function addToTree(nodes, targetId, node) {
  if (targetId == null) { nodes.push(node); return true }
  for (const n of nodes) {
    if (n.id === targetId) { if (!n.children) n.children = []; n.children.push(node); return true }
    if (n.children && addToTree(n.children, targetId, node)) return true
  }
  return false
}

function editRename() {
  if (!editFolderId.value || !editFolderName.value.trim()) return
  const f = findFolderById(editFolders.value, editFolderId.value)
  if (f) f.name = editFolderName.value.trim()
  addEditOp('rename', editFolderId.value, { name: editFolderName.value.trim() })
  editFolderName.value = ''
}

function editDelete() {
  if (!editFolderId.value) return
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return
  const removedIds = new Set(collectAllIds([folder]))
  const parentId = folder.parentId
  addEditOp('delete', folder.id, {})
  removeFromTree(editFolders.value, folder.id)
  // 不再通过 key 强制重建 el-tree，因此父目录的展开状态不会因删除兄弟节点而丢失。
  editExpandedKeys.value = editExpandedKeys.value.filter(id => !removedIds.has(id))
  if (parentId != null && editExpandedKeys.value.includes(parentId)) expandEditedFolder(parentId)
  editFolderId.value = null; moveInSource.value = null
}

function editMoveUp() { shiftFolderLocal(-1) }
function editMoveDown() { shiftFolderLocal(1) }

function shiftFolderLocal(delta) {
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder) return
  // 找到上级节点的 children 数组
  const parentNode = folder.parentId ? findFolderById(editFolders.value, folder.parentId) : null
  const list = parentNode?.children || editFolders.value
  // 按当前 sortOrder 排序找位置
  list.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  const idx = list.findIndex(f => f.id === folder.id)
  const nidx = idx + delta
  if (nidx < 0 || nidx >= list.length) return
  // 在数组中交换位置
  const tmp = list[idx]; list[idx] = list[nidx]; list[nidx] = tmp
  // 更新 sortOrder
  list.forEach((f, i) => { f.sortOrder = (i + 1) * 10 })
  for (const f of list) {
    addEditOp('move', f.id, { parentId: f.parentId, sortOrder: f.sortOrder })
  }
}

function editMoveOut() {
  const folder = findFolderById(editFolders.value, editFolderId.value)
  if (!folder || !folder.parentId) return
  const parent = findFolderById(editFolders.value, folder.parentId)
  const newParentId = parent?.parentId ?? null
  removeFromTree(editFolders.value, folder.id)
  folder.parentId = newParentId
  addToTree(editFolders.value, newParentId, folder)
  addEditOp('move', folder.id, { parentId: newParentId, sortOrder: 99 })
  if (newParentId != null) expandEditedFolder(newParentId)
}

function startMoveIn() {
  const f = findFolderById(editFolders.value, editFolderId.value)
  if (!f) return
  moveInSource.value = { id: f.id, name: f.name }
}

function moveFolderToLocal(targetId) {
  if (!moveInSource.value) return
  const f = findFolderById(editFolders.value, moveInSource.value.id)
  if (!f) return
  removeFromTree(editFolders.value, f.id)
  f.parentId = targetId
  addToTree(editFolders.value, targetId, f)
  addEditOp('move', f.id, { parentId: targetId, sortOrder: 99 })
  editFolderId.value = targetId
  moveInSource.value = null
  expandEditedFolder(targetId)
}

function openNewFolderForm() { newFolderName.value=''; newFolderParentId.value=selectedFolderId.value; showNewFolderForm.value=true }
async function createFolder() {
  if(!newFolderName.value.trim())return
  try{await api.post('/folders',{name:newFolderName.value.trim(),parentId:newFolderParentId.value});newFolderName.value='';showNewFolderForm.value=false;loadFolders()}
  catch(e){alert('创建失败：'+(e.response?.data?.message||e.message))}
}

function startResize(e,side){resizing.value=side;e.preventDefault()}
function onResize(e){
  if(resizing.value==='left')leftWidth.value=Math.max(160,Math.min(400,e.clientX-6))
  else if(resizing.value==='right')rightWidth.value=Math.max(240,Math.min(500,window.innerWidth-e.clientX-6))
}
function stopResize(){
  resizing.value=null
}

function openImportDialog(){isEditing.value=false;editPaperId.value=null;uploadFile.value=null;doiInput.value='';doiPdfUrl.value='';form.value={...makeEmptyForm(),folderId:selectedFolderId.value};dialogVisible.value=true}
function openEditDialog(paper){isEditing.value=true;editPaperId.value=paper.id;uploadFile.value=null;form.value={...paper};dialogVisible.value=true}
function resetImportMetadataForNewFile(){
  form.value={
    ...form.value,
    title:'', authors:'', year:null, source:'', doi:'', arxivId:'', sourceUrl:'',
    abstractText:'', keywords:''
  }
  doiInput.value=''
  doiPdfUrl.value=''
}
function setUploadFile(file){
  if(!file)return
  resetImportMetadataForNewFile()
  uploadFile.value=file
}
function onFileChange(e){
  const f=e.target.files?.[0]
  if(f)setUploadFile(f)
  // 允许用户移除后再次选择同一个 PDF。
  e.target.value=''
}
function onDropFile(e){
  const f=e.dataTransfer?.files?.[0]
  if(f?.name?.toLowerCase().endsWith('.pdf'))setUploadFile(f)
}

/** Crossref DOI → 自动提取元数据 */
async function fetchDoi(){
  if(!doiInput.value.trim())return
  fetchingDoi.value=true
  doiPdfUrl.value=''
  try{
    const res=await fetch(`https://api.crossref.org/works/${encodeURIComponent(doiInput.value.trim())}`)
    if(!res.ok) throw new Error('DOI 未找到')
    const r=await res.json()
    const d=r.message
    if(!d)throw new Error('DOI 未找到')
    form.value.doi=doiInput.value.trim()
    if(!form.value.title&&d.title&&d.title[0])form.value.title=decodeHtmlEntities(d.title[0])
    if(!form.value.source&&d['container-title']&&d['container-title'][0])form.value.source=decodeHtmlEntities(d['container-title'][0])
    if(d.author)form.value.authors=d.author.map(a=>(a.given||'')+' '+(a.family||'')).join(', ')
    if(d['published-print']?.dateParts)form.value.year=d['published-print'].dateParts[0][0]
    else if(d['created']?.dateParts)form.value.year=d['created'].dateParts[0][0]
    if(!form.value.sourceUrl&&d.URL)form.value.sourceUrl=d.URL
    doiPdfUrl.value=(d.link||[]).find(link => /application\/pdf/i.test(link['content-type']||''))?.URL || ''
    if(!form.value.abstractText&&d.abstract)form.value.abstractText=decodeHtmlEntities(d.abstract.replace(/<[^>]+>/g,'')).slice(0,2000)
    ElMessage.success('DOI 元数据获取成功')
  }catch(e){ElMessage.error('DOI 获取失败：'+(e.message))}
  finally{fetchingDoi.value=false}
}

/** PDF 自动识别 → 后端提取 DOI/arXiv ID 并返回元数据 */
async function autoIdentify() {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择 PDF')
    return
  }
  enriching.value = true
  try {
    const fd = new FormData()
    fd.append('file', uploadFile.value)
    const res = await api.post('/papers/enrich-metadata', fd, { timeout: 25000 })
    fillFormFromEnrichment(res.data)
    if (res.data.found) {
      ElMessage.success('已自动填充元数据')
    } else {
      ElMessage.info(res.data.message || '未识别到 DOI/arXiv ID，请手动填写')
    }
  } catch (e) {
    ElMessage.error('自动识别失败：' + (e.response?.data?.message || e.message))
  } finally {
    enriching.value = false
  }
}

function decodeHtmlEntities(value) {
  if (!value || typeof document === 'undefined' || !String(value).includes('&')) return value
  const textarea = document.createElement('textarea')
  textarea.innerHTML = String(value)
  return textarea.value
}

function fillFormFromEnrichment(data) {
  const empty = v => v == null || v === '' || (typeof v === 'string' && v.trim() === '')
  if (empty(form.value.title) && data.title) form.value.title = decodeHtmlEntities(data.title)
  if (empty(form.value.authors) && data.authors) form.value.authors = formatAuthors(data.authors)
  if (empty(form.value.year) && data.year) form.value.year = data.year
  if (empty(form.value.source) && data.source) form.value.source = decodeHtmlEntities(data.source)
  if (empty(form.value.doi) && (data.doi || data.foundDoi)) form.value.doi = data.doi || data.foundDoi
  if (empty(form.value.arxivId) && (data.arxivId || data.foundArxivId)) form.value.arxivId = data.arxivId || data.foundArxivId
  if (empty(form.value.sourceUrl) && data.sourceUrl) form.value.sourceUrl = data.sourceUrl
  if (empty(form.value.abstractText) && data.abstractText) form.value.abstractText = decodeHtmlEntities(data.abstractText)
  if (empty(form.value.keywords) && data.keywords) form.value.keywords = data.keywords
}

async function submitPaper() {
  try{
    if (!isEditing.value && !form.value.title?.trim()) {
      ElMessage.warning('请先点击“自动填充元数据”或手动填写论文标题')
      return
    }
    if(isEditing.value){
      await api.put(`/papers/${editPaperId.value}`,form.value)
      ElMessage.success('论文已保存')
    } else {
      await uploadPaper(false)
    }
    dialogVisible.value=false;uploadFile.value=null;await loadPapers()
  }catch(e){
    console.error('导入/保存失败', e)
    const duplicate = e.response?.status === 409 || e.response?.data?.code === 409
    if (!isEditing.value && duplicate) {
      try {
        await ElMessageBox.confirm('文献已存在，是否覆盖？', '文献已存在', {
          confirmButtonText: '覆盖', cancelButtonText: '取消', type: 'warning'
        })
        await uploadPaper(true)
        dialogVisible.value = false
        uploadFile.value = null
        await loadPapers()
      } catch (confirmError) {
        if (confirmError !== 'cancel' && confirmError !== 'close') {
          ElMessage.error('覆盖失败：' + (confirmError.response?.data?.message || confirmError.message))
        }
      }
      return
    }
    ElMessage.error('操作失败：' + (e.response?.data?.message || e.message))
  }
}

async function uploadPaper(overwrite) {
  const fd = new FormData()
  if (uploadFile.value) fd.append('file', uploadFile.value)
  Object.entries(form.value).forEach(([k, v]) => { if (v != null && v !== '') fd.append(k, v) })
  if (overwrite) fd.append('overwrite', 'true')
  const res = await api.post('/papers/upload', fd)
  ElMessage.success(overwrite ? '文献已覆盖' : '论文导入成功')
  const paper = res.data.paper
  const taskId = res.data.taskId
  if (paper?.id && taskId) watchImport(paper.id, taskId)
}
async function triggerAiAnalysis() {
  const paperId = currentPaper.value?.id
  if (!paperId) return

  await runAi(async ({ signal, setStage }) => {
    setStage('已提交，等待分析完成…')
    await api.post(`/agent/process/${paperId}`, {}, { signal })

    await waitForAnalysis(api.get.bind(api), paperId, signal, data => {
      paperAnalysis.value = data
    })

    await Promise.all([loadPapers(), loadAnalysis(paperId)])
  })
}

function cancelAiWithHint() {
  cancelAi()
  ElMessage.info('已取消')
}

async function loadAnalysis(paperId) {
  paperAnalysis.value = null
  try { const r = await api.get(`/agent/analysis/${paperId}`); if (r.data) paperAnalysis.value = r.data } catch (e) {}
}

async function savePaper(p) {
  try {
    const saved = await api.put(`/papers/${p.id}`, p)
    const updated = saved?.data || saved
    if (updated && typeof updated === 'object') {
      Object.assign(p, updated)
    }
    syncPaperInList(p)
    return saved
  } catch (e) {
    ElMessage.error('保存失败：' + (e.response?.data?.message || e.message))
    throw e
  }
}

function currentRecommendation() {
  return currentPaper.value ? ensureRec(currentPaper.value.id) : null
}

async function recommendDetailMetadata() {
  const paper = currentPaper.value
  const rec = currentRecommendation()
  if (!paper || !rec) return
  rec.loading = true
  rec.error = null
  rec.status = '正在识别元数据'
  try {
    const response = await api.post(`/papers/${paper.id}/enrich-metadata`)
    rec.result = { ...(rec.result || {}), metadata: response.data }
    metadataRecommendation.value = response.data
    metadataRecommendDialogVisible.value = true
    rec.status = '已完成'
  } catch (e) {
    rec.error = e.response?.data?.message || e.message || '元数据识别失败'
  } finally {
    rec.loading = false
  }
}

function openMetadataRecommendationDialog() {
  metadataRecommendation.value = currentImportRec.value?.result?.metadata || null
  metadataRecommendDialogVisible.value = true
}

async function confirmRecommendedMetadata() {
  const paper = currentPaper.value
  const metadata = metadataRecommendation.value
  if (!paper || !metadata || !hasMetadataRecommendation.value) return
  try {
    await applyRecommendedMetadata(paper.id, metadata)
    metadataRecommendDialogVisible.value = false
  } catch (e) {
    ElMessage.error('元数据应用失败：' + (e.response?.data?.message || e.message))
  }
}

async function recommendDetailTags() {
  const paper = currentPaper.value
  const rec = currentRecommendation()
  if (!paper || !rec) return
  rec.loading = true
  rec.error = null
  rec.status = '正在推荐标签'
  try {
    const response = await api.post('/agent/tag-suggestions', { paperId: paper.id })
    rec.result = { ...(rec.result || {}), tags: response.data || [] }
    rec.status = '已完成'
  } catch (e) {
    rec.error = e.response?.data?.message || e.message || '标签推荐失败'
  } finally {
    rec.loading = false
  }
}

async function recommendDetailFolder() {
  const paper = currentPaper.value
  const rec = currentRecommendation()
  if (!paper || !rec) return
  rec.loading = true
  rec.error = null
  rec.status = '正在推荐文件夹'
  try {
    const response = await api.post('/agent/folder-suggest', { paperId: paper.id })
    rec.result = { ...(rec.result || {}), folder: response.data }
    rec.status = '已完成'
  } catch (e) {
    rec.error = e.response?.data?.message || e.message || '文件夹推荐失败'
  } finally {
    rec.loading = false
  }
}

function openTagRecommendationDialog() {
  tagRecommendSelected.value = [...(currentImportRec.value?.result?.tags || [])]
  tagRecommendDialogVisible.value = true
}

async function confirmRecommendedTags() {
  if (!currentPaper.value) return
  try {
    await applyRecommendedTags(currentPaper.value.id, tagRecommendSelected.value)
    tagRecommendDialogVisible.value = false
  } catch (e) {
    ElMessage.error('标签应用失败：' + (e.response?.data?.message || e.message))
  }
}

function openFolderRecommendationDialog() {
  const suggestion = currentImportRec.value?.result?.folder
  if (!suggestion) return

  const rawName = String(suggestion.newName || '')
  const parts = rawName.split(/\s*(?:->|\/|>|下的)\s*/).map(part => part.trim()).filter(Boolean)
  recommendNewFolderName.value = parts.at(-1) || ''

  const hasParentValue = Object.prototype.hasOwnProperty.call(suggestion, 'parentFolderId')
  const directParent = suggestion.parentFolderId
  if (directParent != null && directParent !== '') {
    recommendNewFolderParentId.value = Number(directParent)
  } else if (parts.length > 1) {
    recommendNewFolderParentId.value = findFolderIdByPath(parts.slice(0, -1).join(' / '))
  } else if (hasParentValue) {
    recommendNewFolderParentId.value = null
  } else {
    recommendNewFolderParentId.value = currentPaper.value?.folderId ?? null
  }
  folderRecommendDialogVisible.value = true
}

async function confirmFolderRecommendation() {
  const paper = currentPaper.value
  const suggestion = currentImportRec.value?.result?.folder
  if (!paper || !suggestion) return

  if (suggestion.recommended != null) {
    if (Number(paper.folderId) === Number(suggestion.recommended)) {
      folderRecommendDialogVisible.value = false
      return
    }
    try {
      await applyRecommendedFolder(paper.id, suggestion.recommended)
      folderRecommendDialogVisible.value = false
    } catch (e) {
      ElMessage.error('文件夹移动失败：' + (e.response?.data?.message || e.message))
    }
    return
  }

  const name = recommendNewFolderName.value.trim()
  if (!suggestion.suggestNew || !name) {
    ElMessage.warning('请填写新文件夹名称')
    return
  }
  recommendFolderSaving.value = true
  try {
    const response = await api.post('/folders', {
      name,
      parentId: recommendNewFolderParentId.value ?? null
    })
    const folderId = response.data?.id
    if (folderId == null) throw new Error('未返回文件夹 ID')
    await applyRecommendedFolder(paper.id, folderId)
    folderRecommendDialogVisible.value = false
  } catch (e) {
    ElMessage.error('文件夹创建失败：' + (e.response?.data?.message || e.message))
  } finally {
    recommendFolderSaving.value = false
  }
}

async function applyRecommendedMetadata(paperId, metadata) {
  await applyRecMetadata(paperId, metadata)
  await selectPaper(paperId)
  await loadPapers()
}
async function applyRecommendedTags(paperId, tags) {
  await applyRecTags(paperId, tags)
  await loadAllTags()
  await selectPaper(paperId)
  await loadPapers()
}
async function applyRecommendedFolder(paperId, folderId) {
  await applyRecFolder(paperId, folderId)
  await loadFolders()
  await selectPaper(paperId)
  await loadPapers()
}
function toggleFolderSearch(){showFolderSearch.value=!showFolderSearch.value;if(showFolderSearch.value)setTimeout(()=>folderSearchRef.value?.focus(),100)}
function startEditTitle(){editTitleText.value=currentPaper.value.title;editingTitle.value=true;setTimeout(()=>titleInputRef.value?.focus(),100)}
async function saveTitle(){editingTitle.value=false;if(editTitleText.value.trim()&&editTitleText.value!==currentPaper.value.title){currentPaper.value.title=editTitleText.value.trim();await savePaper(currentPaper.value)}}
async function confirmDelete(paper){
  try{await ElMessageBox.confirm('确定删除这篇论文？', '确认删除',{confirmButtonText:'删除',cancelButtonText:'取消',type:'warning'});await deletePaper(paper)}
  catch{}
}
async function deletePaper(paper){await api.delete(`/papers/${paper.id}`);if(currentPaper.value?.id===paper.id)currentPaper.value=null;loadPapers();selectedPaperIds.value=selectedPaperIds.value.filter(id=>id!==paper.id)}

// ===== 批量操作 =====
function openBatchMoveDialog() {
  if (!selectedPaperIds.value.length) return
  batchMoveFolderId.value = null
  batchMoveDialogVisible.value = true
}

async function doBatchMove() {
  batchMoving.value = true
  try {
    await api.post('/papers/batch/move', { ids: selectedPaperIds.value, folderId: batchMoveFolderId.value })
    ElMessage.success('批量移动成功')
    batchMoveDialogVisible.value = false
    selectedPaperIds.value = []
    await loadPapers()
  } catch (e) {
    ElMessage.error('批量移动失败：' + (e.response?.data?.message || e.message))
  } finally {
    batchMoving.value = false
  }
}

async function confirmBatchDelete() {
  if (!selectedPaperIds.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedPaperIds.value.length} 篇论文？此操作不可恢复。`, '确认批量删除', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await api.post('/papers/batch/delete', selectedPaperIds.value)
    ElMessage.success('批量删除成功')
    selectedPaperIds.value = []
    currentPaper.value = null
    await loadPapers()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量删除失败：' + (e.response?.data?.message || e.message))
    }
  }
}

async function handleExport(cmd) {
  try {
    if (cmd === 'bibtex-single') {
      if (!currentPaper.value) return
      const res = await exportSingleBibTeX(currentPaper.value.id)
      downloadBlob(res.data, `${currentPaper.value.title || 'paper'}.bib`)
    } else if (cmd === 'bibtex-batch') {
      const res = await exportBatchBibTeX(selectedPaperIds.value)
      downloadBlob(res.data, 'papers.bib')
    } else if (cmd === 'obsidian') {
      const data = await syncObsidian(selectedPaperIds.value)
      ElMessage[data.success ? 'success' : 'error'](data.message || `已同步 ${data.count} 篇`)
    } else if (cmd === 'zotero') {
      const data = await syncZotero(selectedPaperIds.value)
      ElMessage[data.success ? 'success' : 'error'](data.message || `已同步 ${data.count} 篇`)
    }
  } catch (e) {
    ElMessage.error('导出失败：' + (e.response?.data?.message || e.message))
  }
}

function goToAnalysis(paperId) {
  if (!paperId) return
  router.push(researchRouteLocation(paperId))
}

function showPaperInfo(paperId) {
  selectPaper(paperId)
}

async function openPaperPdf(row) {
  if (!row.pdfPath) return
  await router.push(researchRouteLocation(row.id))
}

function openCurrentPaperPdf() {
  if (currentPaper.value?.pdfPath) {
    router.push(researchRouteLocation(currentPaper.value.id, { mode: 'analysis' }))
  }
}

async function handlePaperAction(cmd, paper) {
  if (cmd === 'edit') {
    openEditDialog(paper)
  } else if (cmd === 'top') {
    await togglePin(paper)
  } else if (cmd === 'delete') {
    confirmDelete(paper)
  }
}

async function togglePin(paper) {
  try {
    await api.post(`/papers/${paper.id}/pin`)
    ElMessage.success(paper.pinned ? '已取消置顶' : '已置顶')
    await loadPapers()
  } catch (e) {
    ElMessage.error('置顶失败：' + (e.response?.data?.message || e.message))
  }
}

/** Agent 推荐文件夹（基于论文标题和现有文件夹列表） */
async function recommendFolder() {
  if (!form.value.title) return
  recommending.value = true
  try {
    const res = await api.post('/agent/folder-suggest', {
      title: form.value.title,
      abstractText: form.value.abstractText || '',
      keywords: form.value.keywords || ''
    })
    const data = res.data
    if (data.recommended) {
      form.value.folderId = data.recommended
      ElMessage.success('已推荐文件夹')
    } else if (data.suggestNew) {
      importFolderRecommendation.value = data
      const rawName = String(data.newName || '')
      const parts = rawName.split(/\s*(?:->|\/|>|下的)\s*/).map(part => part.trim()).filter(Boolean)
      importNewFolderName.value = parts.at(-1) || ''
      importNewFolderParentId.value = data.parentFolderId != null
        ? Number(data.parentFolderId)
        : parts.length > 1 ? findFolderIdByPath(parts.slice(0, -1).join(' / ')) : form.value.folderId ?? null
      importFolderRecommendDialogVisible.value = true
    } else {
      ElMessage.info('暂无文件夹推荐')
    }
  } catch (e) {
    ElMessage.error('文件夹推荐失败')
  } finally {
    recommending.value = false
  }
}

async function confirmImportFolderRecommendation() {
  const name = importNewFolderName.value.trim()
  if (!name) {
    ElMessage.warning('请填写新文件夹名称')
    return
  }
  importFolderSaving.value = true
  try {
    const response = await api.post('/folders', {
      name,
      parentId: importNewFolderParentId.value ?? null
    })
    const folderId = response.data?.id
    if (folderId == null) throw new Error('未返回文件夹 ID')
    form.value.folderId = folderId
    await loadFolders()
    importFolderRecommendDialogVisible.value = false
    ElMessage.success('已推荐文件夹')
  } catch (e) {
    ElMessage.error('文件夹创建失败：' + (e.response?.data?.message || e.message))
  } finally {
    importFolderSaving.value = false
  }
}

async function initLibrary() {
  await Promise.all([loadFolders(), loadAllTags(), loadPapers()])
}
function handleDocClick(e) {
  if (showFolderSearch.value && folderSearchWrapRef.value && !folderSearchWrapRef.value.contains(e.target)) {
    showFolderSearch.value = false
  }
  const newFolderBtnEl = newFolderBtnRef.value?.$el
  if (showNewFolderForm.value && newFolderFormRef.value && !newFolderFormRef.value.contains(e.target)
      && !(newFolderBtnEl && newFolderBtnEl.contains(e.target))) {
    showNewFolderForm.value = false
  }
}
let libraryEventsAttached = false
function attachLibraryEvents() {
  if (libraryEventsAttached) return
  libraryEventsAttached = true
  document.addEventListener('click', handleDocClick)
}
function detachLibraryEvents() {
  if (!libraryEventsAttached) return
  libraryEventsAttached = false
  document.removeEventListener('click', handleDocClick)
}
onMounted(async () => {
  try {
    await initLibrary()
  } finally {
    attachLibraryEvents()
  }
})
onActivated(() => {
  attachLibraryEvents()
})
onDeactivated(detachLibraryEvents)
onUnmounted(detachLibraryEvents)
</script>

<style scoped>
.library { display:flex; height:calc(100vh - 61px); }
.library.is-resizing { user-select:none; }

/* ===== 三栏配色 ===== */
.left-panel { flex-shrink:0; overflow-y:auto; padding:10px 14px; transition:width 0.2s; background:var(--ra-bg); display:flex; flex-direction:column; }
.left-panel.collapsed { padding:0; overflow:hidden; }
.filter-section { margin-top:auto; padding:6px 0 12px; border-top:1px solid var(--ra-border-light); }
.filter-section h4 { margin:4px 0 6px; font-size:14px; font-weight:600; color:var(--ra-text); }
.filter-group { margin-bottom:6px; }
.filter-label { display:block; font-size:11px; color:var(--ra-text-tertiary); margin-bottom:2px; }
.center-panel { flex:1; display:flex; flex-direction:column; overflow:hidden; padding:0 12px; background:var(--ra-panel-bg); }
.right-panel { flex-shrink:0; overflow-y:auto; padding:8px 0 0 12px; transition:width 0.2s; background:var(--ra-bg); }

/* 顶栏 */
.panel-header { display:flex; align-items:center; gap:4px; padding:6px 0; }
.header-search { display:flex; align-items:center; gap:2px; }
.search-input { width:130px; }

/* 树 */
.folder-all { display:flex; align-items:center; gap:4px; padding:5px 8px; cursor:pointer; font-size:13px; border-radius:4px; margin-bottom:2px; color:var(--ra-text); }
.folder-all:hover { background:var(--ra-hover-bg); }
.folder-all.active { color:var(--ra-active-text); font-weight:600; background:var(--ra-active-bg); }
.folder-all.sub { padding-left:20px; }
.folder-tree { background:transparent; padding-left:8px; }
.tree-node-label { font-size:13px; display:flex; justify-content:space-between; width:100%; }
.tree-node-label.path-0 { color:var(--ra-active-text); font-weight:600; }
.tree-node-label.path-1 { color:#79bbff; font-weight:600; }
.tree-node-label.path-2 { color:#a0cfff; font-weight:600; }
.folder-count { color:var(--ra-text-tertiary); font-size:11px; margin-right:8px; }
.el-tree-node.is-current>.el-tree-node__content,
.el-tree-node.is-current>.el-tree-node__content:hover { background-color:var(--ra-active-bg) !important; }

.inline-form { display:flex; gap:12px; margin-bottom:8px; align-items:center; }
.inline-form-fields { display:flex; gap:3px; flex:1; min-width:0; }
.inline-form-actions { display:flex; gap:2px; flex-shrink:0; }
.inline-form-actions :deep(.el-button + .el-button) { margin-left: 0 !important; }
.w-full { width:100%; }

/* 分割线 */
.divider { width:1px; flex-shrink:0; cursor:col-resize; position:relative; background:var(--ra-border); transition:width 0.15s,background 0.15s; }
.divider:hover { background:var(--ra-text-tertiary); }
.divider.active { width:4px; background:var(--ra-link); }
.divider-handle { position:absolute; top:50%;left:50%;transform:translate(-50%,-50%);width:2px;height:28px;border-radius:2px;background:var(--ra-text-tertiary);opacity:0;transition:opacity 0.15s; }
.divider:hover .divider-handle { opacity:1; }
.divider.active .divider-handle { opacity:0; }

/* 工具栏 */
.toolbar { display:flex; align-items:center; justify-content:space-between; padding:4px 0 6px; gap:4px; }
.toolbar-left, .toolbar-right { display:flex; align-items:center; gap:2px; }
.toolbar-search { width:150px; }
.toolbar-divider { display:inline-block; width:1px; height:16px; background:var(--ra-border); margin:0 3px; vertical-align:middle; }

/* 表格 */
.table-wrapper { flex:1; overflow:auto; }
/* 空状态 */
.empty-row { text-align:center; color:var(--ra-text-tertiary); padding:40px 10px !important; cursor:default !important; }
.empty-row.onboarding { padding:60px 20px !important; }
.onboard-box h3 { font-size:18px; color:var(--ra-text); margin:0 0 8px; }
.onboard-box p { font-size:14px; color:var(--ra-text-tertiary); margin:0 0 20px; }
.onboard-actions { display:flex; gap:12px; justify-content:center; }

/* 右栏详情 */
.detail-header { display:flex; align-items:flex-start; justify-content:space-between; gap:8px; margin-bottom:6px; }
.detail-title-row { display:flex; flex-direction:column; align-items:flex-start; gap:8px; flex:1; min-width:0; }
.detail-title { font-size:18px; font-weight:600; margin:0; cursor:text; line-height:1.4; width:100%; }
.detail-title:hover { background:var(--ra-hover-bg); border-radius:3px; }
.detail-ai-status { display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:8px; font-size:12px; }
.detail-ai-status .stage-text { color:var(--ra-link); }
.detail-ai-status .error-text { color:#f56c6c; }
.stage-text { font-size:12px; color:var(--ra-link); margin-left:4px; }
.error-text { font-size:12px; color:#f56c6c; margin-left:4px; }
.recommend-dialog-tip { color:var(--ra-text-tertiary); font-size:12px; line-height:1.5; }
.recommend-tag-list { display:flex; flex-direction:column; gap:8px; }
.folder-recommend-ok { color:var(--el-color-success); }
.metadata-preview-grid { max-height:420px; overflow-y:auto; border-top:1px solid var(--ra-border-light); }
.metadata-preview-row { display:grid; grid-template-columns:64px minmax(0,1fr); gap:12px; padding:8px 0; border-bottom:1px solid var(--ra-border-light); font-size:13px; line-height:1.5; }
.metadata-preview-label { color:var(--ra-text-tertiary); }
.metadata-preview-value { white-space:pre-wrap; word-break:break-word; }
.metadata-preview-value.empty { color:var(--ra-text-tertiary); }
.title-input { font-size:18px; font-weight:600; width:100%; }
.title-input :deep(.el-textarea__inner) { border:1px solid var(--ra-link); border-radius:3px; padding:2px 6px; font-size:18px; font-weight:600; line-height:1.4; resize:none; min-height:32px; }
.detail-divider { height:1px; background:var(--ra-border-light); margin:10px 4px 14px; }
.detail-item { margin-bottom:12px; font-size:13px; line-height:1.6; }
.detail-item .label { font-size:12px; color:var(--ra-text-tertiary); display:block; margin-bottom:2px; }
.extracted-text { margin:0; font-size:12px; color:var(--ra-text-secondary); line-height:1.5; max-height:120px; overflow-y:auto; white-space:pre-wrap; }
/** 上传区域 */
.upload-zone { border:2px dashed var(--ra-border); border-radius:6px; padding:20px; text-align:center; cursor:pointer; transition:border-color 0.2s; margin-bottom:12px; }
.upload-zone:hover { border-color:var(--ra-link); }
.upload-text { font-size:14px; color:var(--ra-text-tertiary); margin-top:6px; }
.upload-text em { color:var(--ra-link); font-style:normal; }
.upload-file { font-size:14px; color:var(--ra-text); margin-top:6px; }
.upload-remove { color:#f56c6c; cursor:pointer; margin-left:8px; font-weight:bold; }

/** DOI 行 */
.doi-row { display:flex; align-items:center; gap:10px; margin-bottom:12px; }
.doi-or { font-size:12px; color:var(--ra-text-tertiary); white-space:nowrap; flex-shrink:0; }
.doi-input-wrap { display:flex; gap:6px; flex:1; flex-wrap:wrap; }
.doi-source-hint { display:flex; align-items:center; gap:10px; margin:-4px 0 10px 70px; color:var(--ra-text-tertiary); font-size:12px; line-height:1.5; flex-wrap:wrap; }
.doi-source-hint a { color:var(--ra-link); }
.import-helper { margin: -2px 0 8px 70px; color:var(--ra-text-tertiary); font-size:12px; line-height:1.5; }

/** 识别结果 */
.import-preview { border-top:1px solid var(--ra-border-light); padding-top:10px; }
.preview-title { font-size:13px; font-weight:600; color:var(--ra-text); margin-bottom:8px; }

/** PDF 全屏预览 */

/* 标签下拉框：每个选项显示删除按钮 */
.tag-option-row { display:flex; align-items:center; justify-content:space-between; width:100%; padding-right:0; box-sizing:border-box; }
.tag-option-row .tag-delete-btn {
  display:inline-block;
  color:#f56c6c;
  font-size:12px;
  cursor:pointer;
  padding:2px 6px;
  border-radius:3px;
}
.tag-option-row .tag-delete-btn:hover { background:#fde2e2; }
.rec-row { display:flex; justify-content:space-between; align-items:center; gap:8px; padding:4px 0; border-bottom:1px solid var(--ra-border-light); font-size:13px; }
.rec-row:last-child { border-bottom:none; }
.rec-row span { flex:1; word-break:break-all; }
</style>

<!-- 非 scoped：强制覆盖 Element Plus 组件内部样式 -->
<style>
.library .panel-header .el-button { padding: 2px 4px !important; min-width: auto !important; }
.library .panel-header .el-button + .el-button { margin-left: 0 !important; }
.library .panel-header .el-dropdown { display: inline-flex !important; }
.library .panel-header .el-tooltip { display: inline-flex !important; }
.library .toolbar .el-button { padding: 2px 4px !important; min-width: auto !important; }
.library .toolbar .el-button + .el-button { margin-left: 0 !important; }
.el-select-dropdown__item:has(.tag-option-row) { padding-right: 8px !important; }

/* 暗色模式：标签下拉与详情区域 */
html.dark .tag-option-row .tag-delete-btn:hover { background: #5c2f2f; }
html.dark .library .folder-tree .el-tree-node__content { color: var(--ra-text); }
html.dark .library .folder-tree .el-tree-node__content:hover { background-color: var(--ra-hover-bg); }
html.dark .library .el-tree-node.is-current>.el-tree-node__content,
html.dark .library .el-tree-node.is-current>.el-tree-node__content:hover { background-color: var(--ra-active-bg) !important; }
html.dark .library .el-input__wrapper,
html.dark .library .el-textarea__inner { background-color: var(--ra-panel-bg); }
html.dark .library .el-select .el-input.is-focus .el-input__wrapper,
html.dark .library .el-input__wrapper.is-focus { box-shadow: 0 0 0 1px var(--ra-link) inset; }
</style>
