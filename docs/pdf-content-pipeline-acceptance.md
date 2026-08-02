# PDF 内容管线验收

## 目标与职责

同一份 PDF 必须支持可追溯的 LLM 理解、精确选择、全文搜索和证据回链：

- PDF.js：canvas 渲染。
- PDFium/WASM：字符命中、选择、搜索和精确矩形。
- PDFBox：服务端版面 block、结构、角色、阅读顺序和粗坐标。

项目代码负责适配、业务锚点、数学辅助和证据组装，不自行重写 PDF 字符引擎。

## 不变量

- 每个产物绑定 PDF SHA-256 和解析器版本；文件变化后旧索引、锚点、结构和记忆失效。
- 搜索和选择共享 PDFium 页面字符范围，不从 DOM span 或矩形顺序反推原文。
- 持久化坐标必须声明坐标空间，并可在 viewport、归一化页面和 PDF point 间转换。
- 选区原文、页面字符、SelectionAnchor 和发送给模型的 evidence 必须可互相核对。
- 论文事实只引用当前版本、能够回到页面与目标区域的 evidence。
- `TEXT / STRUCTURED / REGION` 分别表达文本、可信结构和视觉区域；REGION 不能证明公式内容。
- 无文字层、页面提取失败、部分索引失败、正常零命中和低置信度必须使用不同状态。
- 默认部署不要求 OCR、GROBID、Marker、MinerU 或云 PDF API。

## 验收矩阵

| 能力 | 必须验证 |
|---|---|
| 搜索 | 查询、页码、字符范围、高亮和滚动目标一致；结果顺序稳定 |
| 文本选择 | 起止字符、原文、quads、栏位和后端锚点一致；不吸入邻栏 |
| 数学正文 | 保留完整字符锚点；可读文本不含控制乱码；LaTeX 精度状态不覆盖原文 |
| 公式框选 | 50%–200% 缩放下区域不变；客户端预览与服务端裁剪一致 |
| 结构解析 | 标题、章节、正文、公式、图表、参考文献和页眉页脚角色可检查 |
| LLM 证据 | 当前问题与选区优先；证据绑定 PDF 版本、页码、block 和坐标 |
| 回链 | 正文优先高亮 PDFium 字符矩形；公式/图表使用 target bbox；近似定位明确标识 |
| 恢复 | KeepAlive、重新挂载、PDF 切换和后端重启后可恢复且无空白页竞态 |

## 黄金样本

- 前端确定性契约：`frontend/tests/fixtures/pdf-content-golden.json` 和 `pdf-interaction-golden.json`。
- 后端版面与 evidence：`backend/src/main/resources/eval/pdf-workbench-golden.json`。
- 真实论文通过 `RA_PDF_INTERACTION_SAMPLE` 或测试 manifest 指向本地文件，不提交路径、截图或受版权保护正文。

真实样本至少覆盖：

- IEEE 双栏数学段落不跨栏选择；
- 参考文献密集文字的精确端点；
- 公式上下方正文独立选择；
- 行内公式密集段落的完整字符范围；
- 独立公式、根号、求和上下限和上下标；
- 搜索结果跨页跳转与精确高亮；
- 无文字层和单页提取失败的差异化降级。

## 阶段门禁

PDF 相关变更按 `Plan → Code → Test → Review` 执行。阶段测试包含相应黄金契约；最终验收执行后端 clean test、前端单测、生产构建、真实 PDF 浏览器回归，并确认是否发生外部模型调用。
