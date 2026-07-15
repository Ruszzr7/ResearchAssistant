# 知识总结（精简版）

> 只保留可复用的设计决策、踩坑和排查方法；完整历史以 Git 为准。

## 1. 编码与项目边界

- 源码、Markdown、YAML、JSON、SQL 和 Shell 统一使用 UTF-8，Shell 使用 LF。
- PowerShell 读取中文文件显式使用 `Get-Content -Encoding utf8`；Bash/WSL 使用 UTF-8 locale；Windows `.bat` 设置 `chcp 65001`。
- 项目是本地单机优先的 Java/Spring Boot 科研 Agent，不为了简历引入 Redis 或人为拆分微服务。

## 2. 后端与数据库

- Controller 负责协议，Service 负责业务，Mapper/Entity 负责持久化；长任务优先返回 taskId。
- MyBatis-Plus 承担常规 CRUD，复杂查询使用显式列和参数化 SQL；MySQL `abstract` 列通过别名映射为 `abstractText`。
- 正式环境由 Flyway 迁移 `backend/src/main/resources/db/migration`；`schema.sql` 和 `schema-upgrade-*.sql` 仅作历史参考。
- 测试使用独立 H2 schema 并关闭 Flyway，不依赖开发库数据。旧库切换前先备份并核验 schema，禁止对未知版本盲目 baseline。

## 3. 文件、PDF 与外部服务

- PDF 通过 `PdfParser` 抽象，PDFBox 是 fallback；Marker/MinerU/Grobid 等外部命令必须有超时和失败回退。
- DOI 提取必须在原始 PDF 文本中匹配，并只允许 DOI 结构内部的空白；不能把整篇文本去空白后再匹配，否则会将 DOI 后面的正文拼入标识符并导致 Crossref 404。
- DOI 只代表论文标识符；Crossref 默认提供元数据和来源页，不保证出版社 PDF 可公开下载。导入界面应显示来源页，并仅在 Crossref 返回公开 PDF 链接时提供打开入口，不能绕过订阅权限。
- PDF 导入的元数据补全不能只依赖 DOI/Crossref：会议论文的 `container-title` 或 `event.name` 可能为空，但首页页眉通常包含会议名称。应在外部元数据为空时，从前几页文本中按 `Proceedings`、`Conference`、`Symposium`、`Workshop` 及常见会议缩写提取出版来源。
- PDFBox 的阅读顺序提取可能丢失会议论文首页页眉中的会议名、DOI 和年份。元数据识别应使用独立的版面顺序解析模式；全文阅读仍使用原来的阅读顺序，避免双栏正文交错。
- 多栏论文使用 PDFBox 按坐标排序会交错左右栏；全文和元数据抽取默认使用 PDF 内容流顺序，并在摘要标题（Index Terms/Introduction）处截断，摘要字段最多保留 3000 字。
- 双栏选取的“跨中缝”必须按双侧证据判断：斜体、根号、上下标等行内数学字形会略压入中缝，但只要同一基线的相邻正文只属于一栏，就应随该栏选取；只有同时连接可信左右栏内容的片段才标为歧义并拒绝作为桥接点。
- PDF 全屏阅读器应锁住 `html/body` 的外层滚动并让 `.pdf-pages` 独占内部滚动；基于虚拟页高的跳页要先挂载目标页附近的小窗口，再设置内部滚动偏移，避免为跳转而渲染整篇文档。
- 受控选择不能直接按 PDF 片段包围盒的 `centerY` 排序：同一行的重音、上标和下标会形成更早/更晚的片段行。应先以片段底边中位数归并为 `selectionOrderY`，再在相同基线内按 x 排序，才能让“从公式起点拖到下一行”保持单向范围。
- 虚拟 PDF 页的 `rendered` 状态只对当前挂载的 canvas/text layer 有效。渲染任务须校验挂载版本和 DOM 连通性；滚动时优先渲染当前页、取消离开视口的 canvas 任务，并在重新挂载时使旧状态失效，否则会出现新页面空白而状态已完成的竞态。
- 文件路径必须限制在配置目录内；PDF 数据和 MySQL 数据分开备份。
- 学术来源和 Embedding 统一经过超时、重试、并发许可和降级策略；“无结果”和“调用失败”应由状态或指标区分。

## 4. LangChain4j 与 Agent

- LangChain4j 1.15.1 是 Java AI 接入层，使用统一模型工厂、AI Services、Tool、ChatMemory 和 Embedding API。
- API Key 可由环境变量覆盖；配置 `RA_MASTER_KEY` 后使用 AES-GCM 保存，否则仅适合本地临时开发。
- `ResearchAiConfig` 使用编程式 AI Service，因为模型配置来自数据库；结构化输出先映射 POJO，失败时走受门禁约束的 JSON fallback/repair。
- Skill Registry 管理原子能力，Planner 只生成计划，PlanExecutor 负责校验和顺序执行；普通对话记忆不与工具 Agent 共享。

## 5. 异步任务与可观测性

- Spring `ThreadPoolTaskExecutor` 负责进程内执行，`async_task` 保存状态、上下文、租约、重试和结果。
- 可恢复任务使用 `task_type + context_json` 重建处理器；数据库条件更新负责 claim，租约回收防止进程崩溃后永久占用。
- 取消、超时、过期和终态转换由状态机保护，迟到回调不能覆盖终态；对外保留兼容的 `PROCESSING/COMPLETED` 字段。
- Actuator 暴露 health/info/metrics；Micrometer 只记录低基数任务、AI、Embedding、外部来源和 RAG 指标。日志不写提示词、论文正文、Provider 响应体或凭据。
- SSE 每次写入后 flush；客户端断开后不再向已关闭 response 写入。

## 6. RAG 与证据

- 标准链路：分片 → Embedding → 向量粗召回 → 可选重排序 → 带来源元数据的上下文 → Agent。
- RAG 版本通过 `rag_index_state + rag_index_version + paper_chunk.index_version` 管理；新版本先 BUILDING/READY，再切换 ACTIVE，失败时保留旧版本。
- `evidenceId` 由论文、索引版本、分片序号和内容 hash 稳定派生，不能信任模型自由生成的证据身份。
- 候选证据必须校验 ID、snippet 长度和包含关系；未经候选集确认的 Agent 证据标记为 `UNVERIFIED`。
- Qdrant 写入或检索失败时路由回退内存；一致性巡检只读比对 active 指针、版本元数据和分片数量。

## 7. Vue、PDF.js 与前端任务

- 前端通过 Vite 代理 `/api`；页面状态使用 Vue refs/reactive 和 composables，长任务轮询统一收敛到任务 API。
- 导入 PDF 时文件名只作为文件展示，不作为论文标题；标题优先取 DOI/arXiv 元数据，其次从 PDF 首屏排版提取，最后要求用户手动填写。元数据已补全时，文件夹推荐同时传递标题、关键词、摘要和完整目录树，并只进行一次结构化 Agent 决策，不再额外走 RAG 召回；先匹配一级主题，再按同级的指标/场景/方法习惯匹配或新建最深层子目录。
- PDF.js 批注保存归一化坐标，渲染时按 viewport 换算；可见页窗口加上下占位高度控制长文档渲染成本。
- PDF.js 的 `PDFDocumentProxy`/`PDFPageProxy` 含有 JavaScript 私有字段，必须放在 Vue `shallowRef` 中，不能使用深度代理；阅读器通过可见页计算和防抖回传当前页，详情字段保存后要同步更新表格行。
- PDF.js 文本层上方的 SVG 批注层不能默认接收鼠标事件，否则会遮挡文本选择；文本选择工具应让 SVG 父层 `pointer-events: none`，仅保留批注图形本身可点击，便签和圈注工具再切换为交互层。
- 批注 Controller 也必须返回统一 `{ code, message, data }` 包络；前端 API 层按 `response.data` 取值，若 Controller 直接返回数组会导致批注加载、保存和 AI 批注结果均为空。
- AI 批注不应强制依赖已完成的论文分析；没有分析记录时可使用 PDF 前几页和摘要生成受限上下文，并用 PDF 文本位置建立归一化锚点坐标，避免生成空 `quads` 导致批注不可见。
- 阅读进度不再作为文库管理页面的展示指标；阅读器保留阅读时间统计，并在页面关闭、切后台或累计约 30 秒时同步时长。旧的页码接口和字段暂时保留，兼容已有数据。
- PDF 元数据识别不能只使用双栏首页的单一种类文本流：内容流适合保留摘要顺序，坐标排序适合恢复左页边距的旋转出版信息。元数据模式应将坐标流中可信的会议/期刊出版行前置，再用内容流提取摘要与关键词。
- 工作流步骤的参数可能是 `Long`、`String` 等标量，也可能是记录/DTO；当标量 Skill 收到只有一个键的参数对象时，先解包其值再做 Jackson 转换，避免 `START_OBJECT` 反序列化异常。
- `paper-import` 只负责元数据、标签和文件夹建议；阅读状态由用户直接维护，深度论文分析是独立的用户触发能力，不能因为分析质量门禁失败而让入库建议整体显示失败。详情页的元数据可单独应用，标签通过选择弹窗应用，文件夹通过确认弹窗移动或新建子文件夹。
- 会议 PDF 的本地元数据兜底应优先读取标题下方作者行、首页页眉/页脚出版信息和 DOI 标签后的拆行片段；只有完整 DOI 后缀才提交外部查询，避免把拆行中间片段误判成 DOI。
- 会议来源和年份应在导入阶段统一走 PDF 出版信息启发式：扫描首页元数据区域并识别通用会议格式；识别不到年份时保留空值，不能用 2025 或授权下载时间替代出版年份。详情区的元数据、标签、文件夹推荐应用入口需在已入库论文上也可见，推荐结果按需获取并通过弹窗确认应用。
- 论文库表格的总列宽由中栏实际宽度计算，操作列固定在右侧；标题和期刊/会议内容使用单元格内独立横向滚动，避免表格整体横向滚动导致操作列消失。
- PDF 出版信息可能被 PDF 文本流拆成左页边距的多行，会议出处识别需要在首页元数据区域合并连续出版信息行；关键词应独立识别 `Keywords`、`Index Terms` 等摘要后字段，缺失时保持空值。
- 详情页元数据“分析”只生成预览，不直接持久化；用户确认后复用论文编辑接口应用。导入流水线错误与详情按需推荐错误必须使用不同状态字段，避免后台任务失败遮蔽已生成的推荐结果。
- 当论文内容只匹配到已有父文件夹且该父文件夹已有子文件夹时，不能直接归档到父目录；应让 Agent 判断同级子主题，若无匹配则建议在父目录下新建子文件夹。
- 本机 Vite 可能使用 `http://[::1]:5173` 访问，后端 CORS 默认白名单需同时覆盖 IPv4 localhost 和 IPv6 loopback，否则带 Origin 的 POST 会被返回 403。
- vxe-table 的动态 DOM 需要 `:deep()` 或全局选择器；大表格和 PDF 查看器使用异步组件。
- PDF.js 文本选择的矩形来自浏览器 viewport，而 AI 批注锚点通常来自 PDF 坐标；手动选择应按页面分组并保存 `coordinateSpace=viewport`，渲染旧批注时再依据 `pageWidth/pageHeight/viewBox` 转换，不能把屏幕像素和 PDF 单位混用。
- 直接实例化 PDF.js `TextLayer` 时，容器必须设置 `--scale-factor = viewport.scale`，并具备其文字层的 `text-size-adjust`、`forced-color-adjust` 与选择样式；否则内部 `calc(var(--scale-factor) * …)` 失效，span 回退为浏览器默认 16px，造成文字选区与 canvas 字形错位。
- 左侧竖排 IEEE 出版信息在 PDFBox 内容流中可能缺失，需对首页做 `sortByPosition=true` 的辅助解析；DOI 标签后的拆行识别必须检查后续片段，不能在 `10.1109/VTC2023-Fall6` 等中间片段处提前返回。
- 文件夹新建建议属于有副作用的操作：Agent 只返回建议和父目录，前端应弹窗展示名称并提供确认/取消，确认后才创建并把导入论文放入该目录。
- PDF 导入重复判定应优先使用 DOI，其次比较已存 PDF 的 SHA-256；冲突用 HTTP 409 返回，前端确认覆盖后重试同一上传请求并带 `overwrite=true`。
- 文件夹推荐的输入应同时包含标题、关键词和摘要；缓存键要包含这些内容的指纹，避免元数据补全后仍返回旧目录建议。提示词需先归纳论文的主题、场景、方法和指标，再匹配目录路径与同级分类习惯。主指标要高于约束或场景词：例如 Ergodic Rate 与现有“和速率”子目录匹配时，不得仅因 URLLC/low-latency 把论文归为 Latency；若无同类子目录才建议在相关父目录下新建。
- PDF 阅读器的“选择”必须是浏览器原生文本选择模式，SVG 批注层在该模式下保持 `pointer-events: none`；高亮/下划线作为对已保存选择范围的显式应用操作。便签分为自由落点和选区锚定两类，后者保存 `anchorQuads + anchorText + notePosition` 并以引导线回指原文；默认工具栏不再新建自由画笔。批注坐标保持归一化，因此缩放只需重渲染页面，不应改变已有批注的位置。
- PDF.js 的视觉 text layer 只能保证可见选择，不能保证双栏阅读顺序或公式语义；后续 PDF 工作台必须以“原生 viewer 交互 + layout-aware Document Graph”双轨实现，并在低置信度页面回退至 GROBID/MinerU 等结构化解析。完整阶段方案见 `docs/pdf-selection-and-workbench-spec.md`。
- PDF 元数据的身份标识与字段兜底必须分开：DOI/arXiv 只扫描首页，标题/摘要/关键词才读取前几页；外部记录返回后还要和本地标题做关键词一致性校验，不一致时丢弃外部结果，以防后页参考文献把无关论文写入表单。
- 批注应以单条持久化对象为交互单位：创建高亮、下划线或便签时立即 POST，拖动/编辑时立即 PUT，删除时立即 DELETE，不能依赖页面级“保存批注”。普通选择模式保持 SVG 不拦截文本，只有便签标记可点击；点击 emoji 用页面内浮层展示内容并提供编辑/删除。
- PDF 提取后的连字符不能全局删除：仅可移除软连字符和“字母-换行-字母”造成的断词，必须保留 cell-free、rate-splitting、end-to-end 等术语中的正常连字符。
- Element Plus 树在局部删除后不应通过递增 `key` 强制重建；这会丢失所有展开状态。应直接修改响应式树数据并按 node key 跟踪展开节点，删除子节点时仅剔除被删除子树的展开 key，保留父目录展开。
- 高亮/下划线的首尾范围调整应作为纯函数测试：跨行选择仅更新首个 quad 的左边界或末个 quad 的右边界，保留中间行，并设置最小宽度，避免拖拽产生反向或零宽标记。
- PDF 原生选择的起点要基于 PDF.js 真实 text span 的屏幕矩形命中，而不是整张 text layer：空白页边距/段首缩进应 `preventDefault` 并清空旧选择，文字边缘仅保留少量像素容错；公式仍需单独的区域选择与版面上下文方案，不能把 native span 选区当作公式语义选择。
- PDF.js 文字层可在渲染完成后按实际 DOM 矩形建立前端 viewport 版面索引：保留 text run、视觉行、单双栏和候选段落，先作为自定义命中/选区的几何输入。该轻量索引不替代后端持久化的 `PaperLayoutArtifact`，也不应在此阶段改写原生 Selection；同一基线的左右栏必须先按 x 间隙拆成两行，避免再次串栏。
- 双栏 PDF 的批注选区不能依赖浏览器原生 `Selection` 的 DOM 顺序。应从渲染文字层的真实 run 矩形建立视觉版面索引，只接受严格命中到水平文字 run 的起止点，并限定同页同栏；有效的 run 片段再转换为 DOM `Range.getClientRects()` 和 viewport 归一化 quads。这样端点落在空白、另一栏或边栏竖排信息时不会把无关文字吸入选区。跨栏、跨页连续段落和公式需要后续显式的阅读顺序/区域语义层，不能靠放宽命中范围猜测。
- 双栏选择不能把“列起始 x 的中点”当作中缝：它通常落在左栏内部。应由稳定左栏文字的右侧边缘与右栏文字的左侧边缘估算中缝；按该中缝把同一 PDF.js 基线的 run 拆开。居中标题/作者行属于独立 masthead 通道；图片旁缩进文字则按其几何中心归入原栏。跨中缝的公式、图注或页眉要标为歧义范围，拒绝作为跨栏选择的桥接点。
- PDF.js 把每个词拆为 span 时，双栏中央白缝可能只有约一个字高；不能仅凭“x 间隙大于字体高度倍数”拆行，否则会把左右栏合成为全宽行并在选择时交错吸入另一栏。应在多个视觉基线上寻找位置稳定、重复出现的中央白缝，以其作为优先拆分边界；普通词间空隙仍沿用保守的大间隙规则。
- 后端 `PaperLayoutArtifact` 不能复用 PDFBox 的无坐标整页字符串作为证据顺序；应收集 `TextPosition`，先按基线聚行，再以跨多行重复的中央白缝拆栏，并按跨栏锚点分段生成“左栏后右栏”的稳定 reading order。横排正文可用 `xDirAdj/yDirAdj`，但旋转页边文字必须改用真实页面 `x/y` 坐标并按原始字形顺序聚合，否则会被误投影到页顶并拆成单字块。

## 8. 安全、部署与验证

- 设置读取端只返回脱敏值和 `configured`；生产缺少 `RA_MASTER_KEY` 时 fail-closed；CORS 禁止通配符。
- API 使用 `{ code, message, data }` 包络，并通过 HTTP 状态表达校验、冲突、容量和外部服务错误；请求 ID 用于排查。
- 默认拓扑为 MySQL + Spring Boot + Nginx/Vue，Qdrant 通过 Compose profile 可选启用；Docker 启动前注入 `.env` 并通过 healthcheck 验证。
- 验证顺序：后端 `mvnw.cmd test`，前端 `npm.cmd run test:unit`、`npm.cmd run build`，部署环境再执行 Compose、备份恢复和健康检查。
- Windows 本地启动不能仅依据 PID 或端口占用判断成功：数据库以 3306 监听、后端以 `/actuator/health`、前端以固定 `127.0.0.1:5173` 的 HTTP 响应为就绪标准；一键脚本应按数据库 → 后端 → 前端顺序调用各独立入口，并在未知进程占端口时拒绝自动结束进程。
