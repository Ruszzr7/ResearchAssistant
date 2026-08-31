# 论文整篇解析 span 化回归（2026-08-28）

## 范围

- 论文：184、185、190、191。
- 生产路径仍为一次整篇模型调用；未增加二次评审、JSON 修复调用、分块汇总或在线语义门禁。
- 原始 `DocumentBlock` 与 bbox 保持不变；调用前确定性合并为同页语义 span，模型引用 span，响应入库前展开为原始 block ID。
- 版面回放不调用模型；真实理解回归每篇调用模型一次。

## 确定性 span 回放

| paper | 非空原始块 | span | 块数下降 | 合并 span | 单 span 最大块数 | 角色纠正 | FORMULA→BODY |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 184 | 482 | 319 | 33.8% | 69 | 6 | 27 | 25 |
| 185 | 268 | 188 | 29.9% | 47 | 7 | 8 | 5 |
| 190 | 1095 | 797 | 27.2% | 145 | 10 | 71 | 61 |
| 191 | 972 | 689 | 29.1% | 131 | 8 | 93 | 81 |

四篇均满足：所有非空 block 被 span 完整覆盖；span→block 映射无缺失；block 页码在 PDF 范围内；bbox 均可用于页面定位。

论文 191 的已知失败句被重组为 `p9-s0027`，映射到：

```text
p9-b0111, p9-b0112, p9-b0115, p9-b0116, p9-b0120, p9-b0121
```

该 span 包含“RSMA 在 K=4、ε=10^-5 时 ET 比 NOMA 高 91.48%”的完整直接依据；同一来源可确定性解析为六个同页页面矩形。此前被误引的 `p10-b0001` 不再出现在这条 91.48% 结论中。

## 一次模型调用真实回归

| paper | 状态 | 输入 token | 输出 token | 耗时 | 贡献 | 发现 | 局限 | 基准结果 | issues |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| 184 | READY | 13496 | 1077 | 30.7 s | 3 | 4 | 3 | 2 | [] |
| 185 | READY | 12032 | 1035 | 26.6 s | 3 | 3 | 2 | 1 | [] |
| 190 | READY | 37336 | 1729 | 48.4 s | 4 | 5 | 3 | 3 | [] |
| 191 | READY | 36426 | 1326 | 41.0 s | 4 | 5 | 2 | 5 | [] |

- 四篇均为 `paper-understanding-v5-semantic-spans`、1/1 完成、`invalidEvidence=0`。
- 四篇最新画像已零模型调用同步到兼容 `paper_analysis`，`token_used` 分别为 14573、13067、39065、37752。
- `sectionDigests` 在整篇路径中不再要求模型生成，消除了旧的 `UNSOURCED_SECTION_DIGEST_DROPPED`。
- 相比 2026-08-27 的 block 输入，本轮四篇输入 token 分别下降约 13.9%、7.8%、9.2%、9.6%。耗时受供应商波动影响，只记录实测值，不作为单次效果结论。
- 论文 185 本轮提取 3 个 models、3 个 metrics、1 条 benchmark，不再是旧结果中的整体空数组。

## 仍保留的边界

- 当前 span 保守限制在单页，跨页续段仍分成两个来源；页面图片和整篇输入可帮助模型理解，但证据加载时需按相邻来源补充上下文。
- `profile_quality_json` 只检查结构、身份和引用 ID 有效性，用于观测而非语义裁判；没有新增在线模型评审。
- 这份结论只覆盖当前四篇，不代表通用论文集质量。

## 临时人工验收样本

为判断当前重构是否达到可用质量，增加了一份可删除的小样本验收：8 条人工核对关键事实、8 个易误判角色块、2 组应重组为同一语义 span 的碎片。它只读取数据库中的最新解析结果，不调用模型，也不进入生产链路。

| 检查项 | 结果 |
|---|---:|
| 非空 block 一对一覆盖 | 4/4 篇 |
| 易误判角色纠正 | 8/8 |
| 已知碎片重组 | 2/2 |
| 关键事实召回 | 8/8 |
| 人工关键事实的直接证据支撑 | 8/8 |
| 已存证据页码与 bbox 有效 | 181/181 |

初次验收发现论文 184 的高移动性结论只引用 Fig. 3 图注 `p4-b0048`。两次仅加强提示的重解析仍会误选图注或无关正文，说明不能依赖模型自行遵守证据角色。最终采用轻量确定性边界：图注文本和页面图像仍提供给模型理解，但图注不再获得画像可引用 span ID；页面来源与后续高亮能力不变。论文 184 再解析后，该结论引用同页直接正文 `p4-b0049`、`p4-b0050`，四篇验收全部通过。

论文 184 最终单次重解析为 READY：输入 13642 tokens、输出 1239 tokens、约 34.9 秒，4 条贡献、5 条发现、3 条局限、3 条基准结果，issues 为空；兼容 `paper_analysis.token_used=14881`。

论文 185 的 ASPA 段落已合并为一个 span，不过原始块仍夹有行内公式拆分形成的 `c 1` 等噪声；本轮模型能够理解，不把它升级为生产架构改造项。

## 可重复命令

```powershell
$env:RUN_PAPER_SPAN_REAL_EVAL='true'
.\mvnw.cmd -q '-Dtest=PaperSemanticSpanFourPaperEvalTest' test

$env:RUN_PAPER_UNDERSTANDING_REAL_EVAL='true'
# 可选：只重解析指定论文；不设置时仍运行 184、185、190、191
$env:PAPER_UNDERSTANDING_EVAL_IDS='184'
.\mvnw.cmd -q '-Dtest=PaperWholePaperFourPaperModelEvalTest' test

$env:RUN_PAPER_PROJECTION_REAL_EVAL='true'
.\mvnw.cmd -q '-Dtest=PaperFourPaperProjectionEvalTest' test

$env:RUN_PAPER_PARSING_QUALITY_EVAL='true'
.\mvnw.cmd -q '-Dtest=PaperParsingFourPaperQualityEvalTest' test
```

第二条命令会真实调用解析模型并更新论文理解记录；第三条只把已就绪画像同步到兼容 `paper_analysis`，不调用模型；第四条使用临时人工样本验收最新结果。这些测试默认不会在全量测试中执行。
