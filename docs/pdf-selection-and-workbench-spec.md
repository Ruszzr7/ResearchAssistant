# PDF 精确选取与论文工作台规格（草案）

状态：Draft，2026-07-14。本文只定义后续实现路线；除已完成的基础批注改进外，不代表本轮立即建设 PDF 内 AI 工作台。

## 1. 目标与边界

目标是把论文 PDF 阅读从“看图 + 另开分析页”变为可追溯的研究工作台：用户能准确选择正文、公式或区域，直接提问、解释、生成批注、做全文分析或把当前论文加入多篇对比。

本规格不把 PDF 当作连续纯文本。系统必须同时保留：

- 原始 PDF 的视觉呈现与原生文本选择；
- 面向 AI 的、去除页眉页脚后的结构化正文；
- 可从 AI 结论回到页码、段落、坐标和原文的证据链。

## 2. 核心产品形态

### 2.1 单篇论文工作台

打开论文后，主区域维持双栏布局：左侧 PDF，右侧“论文助手”。右侧始终显示当前上下文状态：全文、某章节、选中段落、选中公式或选中区域。

用户在 PDF 中选中文字后，右侧对话框可以进行问答，并提供解释、翻译等功能。用户不选内容时，右侧助手默认以“全文”工作，可执行全文概览、方法拆解和实验结论提取。

### 2.2 独立页面的定位

“论文分析”保留为报告和历史结果中心；“研究空白/对比”保留为多论文、跨文件夹任务中心。PDF 工作台是这些能力的上下文入口，而不是重复实现：点击“全文分析”在右侧展示概要，并允许打开完整报告；点击“加入对比”把当前论文和证据锚点传给独立对比工作流。

## 3. 为什么当前文本选取不可靠

PDF 的视觉字形、内部文本顺序和语义段落可能完全不同：双栏文字可能交错、标题可能拆为多个 span、公式可能是绘图路径或分散字形。仅依赖浏览器的 `window.getSelection()` 可以得到屏幕范围，但不能保证得到正确语义文本。

PDF.js 的 display layer 可提供页面、viewport 和 `TextContent`；每个 `TextItem` 含文本、变换矩阵、宽高、字体等信息。其官方 viewer 本身拥有经过长期维护的 text layer、缩放、选择和 annotation/editor 层，因此应作为交互层实现的参照，而不是自行从 canvas 上猜文字。[PDF.js Getting Started](https://mozilla.github.io/pdf.js/getting_started/?lang=en) [PDF.js API: TextItem](https://mozilla.github.io/pdf.js/api/draft/module-pdfjsLib.html)

## 4. 双轨文档模型

### 4.1 交互轨：PDF.js Viewer Layer

职责：呈现原始页、缩放、搜索、原生拖选、视觉批注、键盘可访问性。

决策：从当前自绘 canvas/text layer 逐步迁移到 PDF.js 的 viewer primitives（`PDFPageView`、`TextLayerBuilder`、event bus、link/annotation layer），或以官方 viewer 作为受控基础二次开发。不能继续只用自定义绝对定位 span 来承担完整选取体验。

要求：

- 文本选择模式下，批注 SVG 容器不得覆盖 text layer；
- 缩放、旋转和 HiDPI 必须共用同一 viewport；
- `Selection` 仅作为用户选择的视觉来源，不能直接当作 AI 正文顺序来源；
- 选择后保存 `page + normalized boxes + anchorText + character offsets + documentHash + parserVersion`，而非只保存屏幕像素。

### 4.2 语义轨：Layout-aware Document Graph

职责：为 AI 问答、全文分析、检索和对比提供正确阅读顺序与结构。

每个解析块至少保存：

```text
DocumentBlock {
  documentHash, parserVersion, page,
  bbox(normalized), role,
  sectionPath, readingOrder,
  text, latex?, tableHtml?, confidence,
  sourceTokens[]
}
```

`role` 包括 `TITLE`、`AUTHOR`、`ABSTRACT`、`BODY`、`HEADING`、`FIGURE`、`CAPTION`、`FORMULA`、`TABLE`、`REFERENCE`、`HEADER`、`FOOTER`、`MARGIN_METADATA`。面向 AI 的默认语料只使用 `ABSTRACT/BODY/HEADING/CAPTION/FORMULA/TABLE`，页眉、页脚、页码、版权声明和左侧出版信息不进入默认上下文。

### 4.3 解析后端与回退

1. PDFBox/PDF.js token 坐标解析作为快速本地层；
2. 根据页面 token 的 x 分布、列间空隙、字号和重复率构建单栏/双栏阅读顺序；
3. 低置信度、扫描件、复杂公式或表格页交给可选外部解析器；
4. 首选外部结果为 MinerU 或 GROBID，保留原始 PDF 坐标以便回链。

GROBID 的全文结果可请求标题、段落、句子、公式、图表等结构的坐标；其官方建议的交互方式正是 PDF.js 显示原始 PDF，再叠加坐标结果。[GROBID PDF coordinates](https://grobid.readthedocs.io/en/latest/Coordinates-in-PDF/) [GROBID FAQ](https://github.com/grobidOrg/grobid/blob/master/doc/Frequently-asked-questions.md)

MinerU 可输出按阅读顺序组织的 JSON/Markdown，支持单栏、多栏、页眉页脚移除、公式 LaTex、表格和版面可视化，适合作为复杂论文或公式页的高质量回退。[MinerU 官方仓库](https://github.com/opendatalab/MinerU)

## 5. 精确选取设计

### 5.1 文本选取

1. 浏览器原生 selection 给出若干 client rect；
2. 按页截断并转为归一化 bbox；
3. 与当前页 `DocumentBlock.sourceTokens` 做 IoU + 文本前后缀匹配；
4. 生成 `SelectionAnchor`，保存命中的 block、token 范围和原生 anchorText；
5. 若匹配置信度低，仍允许“按视觉区域提问”，但 UI 标示“区域上下文”，不能伪装为精确文字锚点。

```text
SelectionAnchor {
  paperId, page, boxes[], anchorText,
  blockIds[], tokenStart?, tokenEnd?,
  selectionKind: TEXT | REGION | FORMULA | TABLE,
  confidence, documentHash, parserVersion
}
```

### 5.2 标题、作者、双栏正文

- 标题/作者：使用官方 text layer；若 PDF 缺失可复制文本，则降级为区域选择，不制造错误文本。
- 双栏：阅读顺序由 layout graph 的列块决定，通常是“左栏自上而下，再右栏自上而下”；不得把 PDFBox 的原始 token 流直接送进 Agent。
- 页眉页脚/左边栏出版信息：通过跨页重复、页面边缘位置、字体尺寸和 `role` 排除；保留在视觉层和元数据层，不进入正文检索。

### 5.3 公式、表格和图

公式不能保证可作为正常字符选取：有的 PDF 用路径绘制，有的把上下标拆散。首期应提供“框选区域”而非伪造逐字符公式选择。区域与 `FORMULA` block 相交时，AI 上下文优先使用解析器给出的 LaTex；没有 LaTex 时返回图片区域并明确标注“公式 OCR/解析置信度不足”。

表格同理：框选后优先使用结构化 HTML/Markdown 表，而非把屏幕上看见的列顺序拼成一行。

### 5.4 质量门控

每页生成 `layoutConfidence`，至少检查：

- 文字是否在左右栏之间异常交错；
- 页眉/页脚重复率；
- block 是否大量跨列；
- 章节标题与段落阅读顺序是否连续；
- 公式/表格区域是否只含绘图对象；
- 可选外部解析结果与 PDF.js token 文本的覆盖率。

低于阈值时触发外部解析；外部解析失败时保留视觉区域模式，不允许低置信度文本作为 AI 引用证据。

## 6. PDF 内 AI Skills 与 Workflows

Skill 是稳定、可测试的原子能力；Workflow 负责多步编排。用户明确点击的命令优先，Agent 只在“提问/分析”入口内决定检索和组合，不得替代用户的高亮、删除、移动文件夹等确定性操作。

### 6.1 建议 Skills

| Skill | 输入 | 输出 | 责任 |
| --- | --- | --- | --- |
| `resolveSelectionContext` | `SelectionAnchor` | 干净正文块、邻近上下文、置信度 | 从视觉选区映射到语义块 |
| `retrievePaperEvidence` | `paperId + query + scope` | 带页码/坐标的证据块 | 论文内检索，不混入页眉页脚 |
| `explainSelection` | 选区上下文 + 指令 | 解释、术语、引用锚点 | 解释一段或一个公式 |
| `askPaper` | 问题 + scope | 有证据引用的回答 | 单篇论文问答 |
| `analyzeFullPaper` | `paperId` | 结构化分析报告 | 全文贡献、方法、实验、局限 |
| `comparePaperSet` | `paperIds + dimensions` | 对比矩阵、证据 | 多篇论文统一维度比较 |
| `createAnchoredAnnotation` | anchor + 内容 + 样式 | 可移动批注 | 保存视觉批注及语义锚点 |
| `groundAnswer` | 草稿回答 + evidence | 通过/拒绝 + 引用 | 阻止无证据结论 |

### 6.2 Workflows

**选区提问**：`resolveSelectionContext → retrievePaperEvidence(邻近范围) → askPaper → groundAnswer → 返回页码/高亮`。

**全文分析**：`ensureLayoutParse → analyzeFullPaper → groundAnswer → 持久化报告 → 右侧摘要 + 打开完整分析页`。

**当前论文加入对比**：`ensureLayoutParse(current + selected) → comparePaperSet → groundAnswer → 对比页/右侧预览`。

**选区生成批注**：`resolveSelectionContext → explainSelection(可选) → createAnchoredAnnotation`。这是用户触发的短工作流，不应调用全文分析。

### 6.3 Agent 路由规则

输入包含 `intent`、`scope`、`SelectionAnchor`、论文 ID 列表和用户问题。路由优先级：

1. 明确 UI 操作：直接调用对应 Skill，不经 Agent 规划；
2. 选区存在：默认 `selection` scope，只补充相邻段落；
3. “全文/本文/作者方法”类提问：`paper` scope；
4. “与 X 对比/多个论文”类提问：`comparison` workflow；
5. 不清楚时由 Agent 询问范围，而不是静默扩大到整库。

## 7. 便签与批注交互规格

- **自由便签**：用户点击便签工具后在页面落点，填写内容；坐标以归一化 `notePosition` 保存，在“管理批注”模式可拖动。
- **锚定便签**：用户先选中正文，再点击便签；保存 `anchorQuads + anchorText + notePosition`。页面绘制选区轮廓，并用引导线连接到便签图标；拖动只改变 `notePosition`，不改变原文锚点。
- **高亮/下划线**：始终是“先选择，后应用”；只保存选择锚点，不靠自由画笔覆盖正文。
- **自由画笔**：从默认工具栏移除。若未来确有审阅草图需求，作为独立“手绘”高级能力，不混称为圈注。

## 8. 数据迁移与兼容性

现有 `coordinates_json` 是可扩展 Map，因此新版 NOTE 可增加：

```json
{
  "coordinateSpace": "viewport",
  "pageWidth": 918,
  "pageHeight": 1188,
  "notePosition": {"x": 0.86, "y": 0.31},
  "anchorQuads": [{"x1": 0.12, "y1": 0.30, "x2": 0.42, "y2": 0.30, "x3": 0.42, "y3": 0.27, "x4": 0.12, "y4": 0.27}],
  "anchorText": "optional text"
}
```

旧 NOTE 只有 `quads` 时，将其解释为 `notePosition`，不显示引导线；旧 FREEHAND 继续渲染，但不再提供新建入口。

## 9. 分阶段实施与验收

### Phase 0：样本与可观测性

建立至少四类真实样本：单栏、IEEE 双栏、左侧/顶部出版信息、含密集公式/扫描页。记录每页 layout 置信度、选择映射置信度、外部解析耗时和失败原因。

验收：用户提供的 WY/ZJP 两篇论文中，正文阅读顺序正确，页眉页脚不会进入 AI 上下文。

### Phase 1：Viewer 选择基础

替换/补足 text layer 为 PDF.js viewer primitives；保留缩放、搜索、原生选择。为每个选区生成 visual boxes 与 `SelectionAnchor`。

验收：标题、摘要、双栏正文可稳定选择；缩放 50%–300% 后同一批注和选区锚点保持对齐。

### Phase 2：结构化解析层

实现 Document Graph、PDFBox 坐标布局检测、header/footer 去除和 GROBID/MinerU 回退适配器。

验收：AI 输入不含重复页眉、页码、版权行；双栏正文不交错；公式/表格按区域降级而非乱码。

### Phase 3：锚定批注与问答

实现自由/锚定便签、拖动、引导线、选区问答和证据回链。

验收：选区提问的每条回答都能跳回页码与标注区域；移动便签不丢失原文锚点。

### Phase 4：全文与跨论文工作流

接入全文分析、加入对比、结果中心跳转和质量门控。

验收：用户能从 PDF 内完成“选区解释”“全文分析”“加入两篇对比”三条路径；Agent 不能把低置信度解析当作可靠证据。

## 10. 风险与决策点

- PDF.js 能保证视觉层和可复制文字，但不能把所有公式图形变成可靠文本；公式需要区域语义与解析器回退。
- GROBID 更适合学术结构与坐标，但不能保证视觉排版完全保留；它负责逻辑结构，不替代原 PDF viewer。
- MinerU 适合复杂版面/公式回退，但部署体积和硬件成本更高，应设置为可选后端而非强制依赖。
- 首期优先保证“正确且可追溯”，不以把所有 PDF 强行转成连续文本为目标。
