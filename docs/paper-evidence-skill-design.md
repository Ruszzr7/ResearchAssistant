# 科研论文 `paper-evidence` Skill 设计方案

## 1. 设计目标

设计一个标准化、可复用、按需触发、渐进披露（Progressive Disclosure）的 Agent Skill：

```text
paper-evidence
```

用于科研论文问答场景中的证据检索。

该 Skill **不负责论文解析和入库**。论文在进入知识库之前已经完成结构化解析，并已经获得：

- 正文语义段落；
- 公式；
- 公式族；
- 图；
- 表；
- 算法；
- 图、表、公式、算法对应的来源单元；
- 页码；
- PDF 页面坐标；
- 阅读顺序；
- 单元之间的引用和结构关系；
- 可供语义检索、关键词检索和精确检索使用的索引。

`paper-evidence` 的职责是：

> 根据用户问题分析回答该问题需要哪些论文证据，从已经解析和索引好的论文数据中持续检索这些证据，对每轮结果进行有效性判断，并最终返回可供上层 Agent 回答、引用、高亮和 PDF 跳转使用的结构化 Evidence Pack。

---

## 2. 核心原则

整个 Skill 遵循以下原则：

1. **LLM 负责语义理解**
   - 分析用户问题；
   - 拆解 Evidence Need；
   - 生成检索意图；
   - 判断证据是否真正解决某个 Evidence Need。

2. **确定性程序负责流程控制**
   - 执行检索；
   - 合并和去重证据；
   - 维护未解决需求集合；
   - 维护失败计数；
   - 控制继续检索或退出。

3. **检索成功不等于“已经回答完问题”**

   一轮检索成功的定义是：

   > 本轮新增证据有效减少了未解决的 Evidence Need。

4. **只要本轮检索仍然取得有效进展，就允许继续检索。**

5. **不预设固定最大检索轮数。**

6. **连续两轮检索都没有减少未解决需求，则立即退出。**

7. **检索的目标不是获得更多 Chunk，而是持续减少 unresolved Evidence Needs。**

8. **Skill 不负责最终自然语言回答。**

   Skill 返回证据，上层 Agent 根据 Evidence Pack 进行：
   - 回答生成；
   - Evidence Attribution；
   - 引用展示；
   - PDF 高亮；
   - 页面跳转。

9. 当前版本只处理**当前指定论文范围内的证据检索**，不实现跨论文证据搜索、跨论文证据融合等扩展功能。

---

## 3. Skill 的本质

`paper-evidence` 不是简单的一段 Prompt，也不是一个固定次数的 RAG 流程。

它应当是标准 Agent / Codex Skill：

```text
paper-evidence
=
Skill Instructions
+
Tool Contracts
+
Structured Schemas
+
Evidence Planning Policy
+
Retrieval Policy
+
Evidence Evaluation Policy
+
Progress Control Policy
+
Exit Policy
```

宿主 Agent 在需要基于论文内容回答问题时按需加载 Skill。

Skill 通过渐进披露告诉 Agent：

```text
什么时候应该检索
↓
需要找什么证据
↓
怎样检索
↓
怎样判断本轮是否有效
↓
下一轮还缺什么
↓
什么时候应该停止
```

---

## 4. Skill 触发条件

建议 Skill Metadata 使用中文描述：

```yaml
---
name: paper-evidence
description: >
  当回答用户问题必须依据已经解析并建立索引的科研论文内容时，使用本技能检索结构化证据。
  本技能负责分析用户问题、规划证据需求、围绕尚未解决的证据需求持续检索有效证据，
  并判断每轮检索是否实质性减少未解决需求。
  只要检索仍取得有效进展就继续；当所有必要证据需求均已解决时完成检索；
  当连续两轮检索都没有产生有效进展时停止。
  返回的证据必须保留来源定位信息，以支持后续引用、PDF 高亮和页面跳转。
---
```

明确不负责：

```text
PDF 解析
OCR
论文入库
文档切分
通用 Web 检索
跨论文证据融合
```

---

## 5. 总体流程

整体流程：

```text
用户问题
   │
   ▼
问题分析
   │
   ▼
Evidence Need 规划
   │
   ▼
初始化未解决需求集合 U0
   │
   ▼
┌────────────────────────────┐
│ 第 t 轮证据检索            │
│                            │
│ 规划本轮检索               │
│       ↓                    │
│ 执行检索                   │
│       ↓                    │
│ 结构扩展 / 重排 / 去重     │
│       ↓                    │
│ Evidence Judge             │
└────────────────────────────┘
   │
   ▼
比较 Ut-1 与 Ut
   │
   ├── Ut = ∅
   │      ↓
   │    COMPLETE
   │
   ├── 未解决需求减少或状态实质改善
   │      ↓
   │    本轮成功
   │      ↓
   │    failure_streak = 0
   │      ↓
   │    继续检索
   │
   └── 未解决需求未减少且无实质改善
          ↓
       本轮失败
          ↓
       failure_streak += 1
          ↓
       failure_streak >= 2 ?
          │          │
         是          否
          │          │
        STOP       继续
```

因此不存在“最多两轮检索”的固定上限。

正确原则是：

> **允许任意数量的有效检索轮次，但不允许连续两轮无进展检索。**

---

## 6. 为什么采用这种退出机制

用户问题复杂程度不同。

简单问题可能只需要：

```text
1 轮检索
```

复杂问题可能需要：

```text
第1轮：找到定义
第2轮：找到核心公式
第3轮：找到公式解释
第4轮：找到实验结果
第5轮：找到作者对结果的讨论
```

只要每一轮都解决新的 Evidence Need 或显著缩小已有 Need 的信息缺口，这些检索就都是有效的，不应因为固定轮数而提前停止。

真正需要防止的是：

```text
搜索
→ 没找到新信息
→ 换个说法继续搜索
→ 仍然没找到
→ 再换个说法继续搜索
→ ...
```

因此退出条件应当是：

```text
连续两轮没有取得 Evidence Coverage Progress
```

而不是：

```text
retrieval_round >= N
```

---

## 7. Question Analysis

### 7.1 目的

Question Analysis 不回答用户问题，只负责理解：

- 用户究竟在问什么；
- 问题类型；
- 涉及哪些实体；
- 是否包含明确的公式、图、表、算法等引用；
- 是否需要多个证据共同支撑；
- 用户要求的是定义、机制、关系、比较还是结果解释。

该部分主要由 LLM 完成。

确定性程序辅助提取显式引用，例如：

```text
Eq. (12)
Equation 12
Fig. 3
Table II
Algorithm 1
Section IV-B
Page 7
```

### 7.2 推荐 Prompt

```text
你正在执行科研论文证据检索前的问题分析。

你的任务不是回答用户问题，而是理解：
1. 用户真正想知道什么；
2. 问题涉及哪些核心概念、变量、方法、公式、图、表或算法；
3. 用户是否显式引用了论文中的某个结构对象；
4. 回答该问题需要单一证据还是多个证据共同支撑；
5. 问题属于定义、机制、关系、比较、结果解释、方法说明或其他哪一类。

不要推测论文中不存在的结论。
不要开始回答用户问题。

请输出结构化结果。
```

### 7.3 推荐输出

```json
{
  "normalized_question": "为什么状态空间使用上一时刻CSI，以及该设计与信道时间相关性有什么关系？",
  "question_types": [
    "mechanism",
    "relationship",
    "explanation"
  ],
  "entities": [
    "state space",
    "previous CSI",
    "channel temporal correlation"
  ],
  "explicit_references": [],
  "retrieval_complexity": "multi_evidence"
}
```

`retrieval_complexity` 可限制为：

```text
exact_lookup
single_evidence
multi_evidence
```

---

## 8. Evidence Need Planning

### 8.1 Evidence Need 的定义

Evidence Need 表示：

> 为了回答用户当前问题，需要从论文中获得什么信息。

它不是检索关键词。

例如用户问题：

```text
为什么状态空间中加入上一时刻 CSI？
这种设计与信道的马尔可夫性有什么关系？
```

可以拆解为：

```json
[
  {
    "id": "N1",
    "description": "找到论文对状态空间组成的定义",
    "type": "definition",
    "critical": true
  },
  {
    "id": "N2",
    "description": "找到上一时刻CSI在状态中的具体表示",
    "type": "definition",
    "critical": true
  },
  {
    "id": "N3",
    "description": "找到论文关于信道时间相关性、马尔可夫性或相邻时隙信道关系的描述",
    "type": "mechanism",
    "critical": true
  },
  {
    "id": "N4",
    "description": "查找论文是否说明历史CSI与状态设计之间的作用关系或设计动机",
    "type": "relationship",
    "critical": false
  }
]
```

### 8.2 推荐 Prompt

```text
根据用户问题和问题分析结果，规划回答该问题所需要的论文证据。

请将所需证据拆分为若干 Evidence Need。

Evidence Need 表示“为了可靠回答问题，必须从论文中找到什么信息”，不是搜索关键词。

要求：
1. 不要预设答案；
2. 不要把尚未证实的因果关系或结论直接写入 Evidence Need；
3. 每个 Evidence Need 应当可检索、可判断、彼此尽量独立；
4. 优先保持需求数量精简，通常为 1～6 个；
5. 用户明确引用且回答时必须使用的公式、图、表、算法或章节，应标记为 critical；
6. critical 表示如果缺少该证据，就不能可靠回答问题的核心部分；
7. 不要扩展到用户没有询问的研究问题。

输出结构化 Evidence Need 列表。
```

---

## 9. Evidence Need 设计约束

### 9.1 不预设答案

错误：

```text
找到“作者因为马尔可夫性所以使用上一时刻CSI”的证据。
```

正确：

```text
找到历史CSI、信道时间相关性和状态设计之间关系的论文证据。
```

### 9.2 控制初始需求数量

建议：

```text
1 <= evidence_needs <= 6
```

Evidence Need 应尽量保持：

```text
可检索
可判断
相互区别
能够映射到实际论文证据
```

### 9.3 Critical Need

`critical = true` 表示：

> 如果该证据缺失，就无法可靠回答用户问题的核心部分。

---

## 10. Question Analysis 与 Evidence Need Planning 的工程实现

逻辑上：

```text
Question Analysis
↓
Evidence Need Planning
```

工程上建议合并为一次结构化 LLM 调用：

```text
plan_evidence(question)
```

推荐总 Prompt：

```text
你正在为科研论文问答执行证据规划。

你的任务不是回答用户问题，而是分析用户的问题，并确定回答该问题必须从当前论文中获得哪些证据。

第一步：理解问题
- 提取用户真正的询问意图；
- 判断问题类型；
- 提取关键概念、变量和显式引用；
- 判断回答是否需要多个证据共同支撑。

第二步：生成 Evidence Need
- Evidence Need 表示回答问题所必需的论文信息；
- Evidence Need 不是搜索词；
- 不得预设尚未证明的答案；
- 保持需求数量精简，通常为 1～6 个；
- 必不可少的需求标记为 critical；
- 不要扩展到用户没有询问的方向。

输出：
1. question_analysis
2. evidence_needs

不要生成最终答案。
```

---

## 11. Unresolved Evidence Need Set

这是整个检索循环的核心状态。

初始：

```text
U0 = {N1, N2, N3, N4}
```

其中：

```text
U = Unresolved Evidence Needs
```

随着证据被找到：

```text
U0 = {N1,N2,N3,N4}

第1轮后：
U1 = {N3,N4}

第2轮后：
U2 = {N4}

第3轮后：
U3 = {}
```

前三轮均取得有效进展，因此可以继续，直到：

```text
U = ∅
```

或连续两轮无法减少 / 改善 `U`。

---

## 12. Need 状态

每个 Evidence Need 至少具有三种状态：

```text
covered
partial
missing
```

含义：

### covered

已经有足够证据支撑该 Need。

### partial

已有相关且有价值的证据，但仍不足以可靠支撑该 Need。

### missing

当前没有能够实质支撑该 Need 的证据。

---

## 13. 如何定义“一轮检索成功”

不能用：

```text
检索到了结果
```

作为成功。

也不能仅使用：

```text
semantic similarity > threshold
```

作为成功。

一轮检索成功应定义为：

> **本轮新增证据使至少一个未解决 Evidence Need 获得实质性状态改善。**

状态改善包括：

```text
missing → partial
partial → covered
missing → covered
```

可以定义：

```text
missing = 0
partial = 1
covered = 2
```

若至少一个 Need 的状态值提高，则存在候选进展。

但为了避免弱相关 Chunk 被误判为成功，还必须满足：

> 新增证据确实缩小了该 Need 的信息缺口，提高了后续回答该 Need 的能力。

因此最终建议：

```text
本轮成功 =
至少一个未解决 Need 的状态实质改善
AND
Evidence Judge 确认新增证据真实减少了该 Need 的 Retrieval Gap
```

---

## 14. 检索失败的定义

如果一轮检索之后：

```text
U_t = U_{t-1}
```

并且所有 Need 的有效状态均未提高：

```text
missing → missing
partial → partial
```

则：

```text
round_success = false
```

即使本轮返回很多 Chunk，只要没有实质减少未解决需求，也应判定为失败。

---

## 15. 失败计数机制

Skill 维护：

```text
failure_streak
```

初始：

```text
failure_streak = 0
```

若本轮成功：

```text
failure_streak = 0
```

若本轮失败：

```text
failure_streak += 1
```

当：

```text
failure_streak >= 2
```

立即停止检索。

例如：

```text
成功
→ 成功
→ 失败
→ 成功
→ 失败
→ 失败
→ STOP
```

是允许的。

核心原则：

> 成功检索会重置失败计数，因为它说明检索仍在有效推进。

---

## 16. 检索循环

```python
failure_streak = 0

while unresolved_needs:

    retrieval_plan = plan_next_retrieval(
        unresolved_needs,
        evidence_pool,
        previous_queries,
        retrieval_gaps
    )

    new_evidence = retrieve(retrieval_plan)

    new_evidence = rerank(new_evidence)
    new_evidence = expand_context(new_evidence)
    new_evidence = deduplicate(new_evidence)

    evidence_pool = merge(
        evidence_pool,
        new_evidence
    )

    judgment = evaluate_evidence(
        question,
        evidence_needs,
        evidence_pool
    )

    progress = compare_need_status(
        previous_need_status,
        judgment.need_status
    )

    if judgment.all_required_needs_resolved:
        return COMPLETE

    if progress.has_meaningful_progress:
        failure_streak = 0
    else:
        failure_streak += 1

    if failure_streak >= 2:
        return PARTIAL_OR_FAILED

    unresolved_needs = judgment.unresolved_needs
    previous_need_status = judgment.need_status
```

不设置固定：

```text
MAX_ROUNDS
```

退出条件只有：

```text
全部需求解决
```

或：

```text
连续两次无有效进展
```

---

## 17. 为什么不会无限检索

### 17.1 Evidence Need 数量有限

初始 Need 集合是有限的。

### 17.2 每轮必须产生状态推进

只有：

```text
missing → partial
partial → covered
missing → covered
```

并且真实缩小 Retrieval Gap，才能被认为成功。

### 17.3 连续两次没有推进立即退出

```text
failed
↓
failed
↓
STOP
```

因此无需设置固定最大轮数也可以防止无限 Tool Calling。

---

## 18. 是否允许动态产生新的 Evidence Need

默认：

> **不允许在检索过程中任意扩展新的 Evidence Need。**

否则可能出现无限扩展：

```text
解决 N1
→ 产生 N5
→ 解决 N5
→ 产生 N6
→ ...
```

只有当某个已有 Need 的完成必须依赖一个论文内部对象时，才允许产生：

```text
dependency_need
```

例如：

```text
N3：理解 Equation 12 的物理意义
```

检索后发现必须先找到 Equation 11 中的变量定义，可以追加：

```text
N3.1 dependency_need
```

但必须满足：

```text
parent_need = N3
```

并且不得作为新的研究方向继续扩张。

---

## 19. 检索工具

Skill 不直接依赖数据库实现，而通过抽象 Tool 使用现有论文索引。

### 19.1 `semantic_search()`

用于：

- 概念；
- 机制；
- 原因；
- 方法；
- 对比；
- 实验解释。

### 19.2 `exact_lookup()`

用于：

```text
Equation
Figure
Table
Algorithm
Section
Page
```

等明确结构对象。

### 19.3 `keyword_search()`

用于：

- 变量；
- 符号；
- 缩写；
- 专业术语；
- 精确名称。

### 19.4 `expand_context()`

利用论文已经解析好的结构关系获得：

```text
previous unit
next unit
references
referenced_by
formula_family
figure source
table source
algorithm source
```

建议：

```text
max_hops = 1
```

必要情况下允许：

```text
max_hops = 2
```

避免无限结构遍历。

---

## 20. Retrieval Planning

每一轮检索都应围绕当前：

```text
Unresolved Needs
```

规划。

例如当前：

```text
covered:
N1
N2

unresolved:
N3
N4
```

则下一轮重点检索：

```text
N3
N4
```

原则：

> 已经 covered 的 Need 不再主动搜索，除非解释另一个未解决 Need 时必须使用。

### 推荐 Prompt

```text
你正在规划下一轮科研论文证据检索。

当前已经有一组 Evidence Need，其中部分已经 covered，部分仍为 partial 或 missing。

你的任务是：
1. 只针对尚未解决或部分解决的 Evidence Need 规划本轮检索；
2. 不重复搜索已经充分 covered 的 Need，除非它是理解其他未解决 Need 的必要依赖；
3. 结合当前 Retrieval Gap 生成检索意图；
4. 避免重复此前已经使用但没有取得有效进展的等价查询；
5. 可以选择语义检索、关键词检索、精确对象查找或结构关系扩展；
6. 检索目标是减少 unresolved Evidence Needs，而不是增加 Chunk 数量。

输出本轮检索计划，不要生成最终答案。
```

---

## 21. Query 规划

Evidence Need 与 Query 必须分离。

例如：

```text
Evidence Need:
找到历史CSI与时间相关信道之间的关系。
```

Query 可以包括：

```text
previous CSI temporal correlation
outdated CSI channel evolution
first-order Markov channel
adjacent slot channel correlation
```

下一轮如果仍未解决，则根据 Retrieval Gap 改写，而不是重复相同 Query。

---

## 22. Retrieval Gap

Evidence Judge 对每一个未解决 Need 应输出：

> 为什么当前证据还不足以解决该 Need。

例如：

```json
{
  "need_id": "N3",
  "status": "partial",
  "gap": {
    "type": "missing_relationship",
    "description": "已经找到信道时间相关模型，但尚未找到该模型与状态设计之间的关系证据"
  }
}
```

下一轮检索直接针对：

```text
missing_relationship
```

而不是重新泛化搜索：

```text
channel
CSI
state
```

---

## 23. Evidence Judge

Evidence Judge 是主要的 LLM 语义判断节点。

它要回答的不是：

```text
这些 Chunk 和问题相关吗？
```

而是：

> 这些证据分别解决了哪些 Evidence Need？解决到什么程度？还有哪些信息缺口？与上一轮相比，本轮是否真实取得了进展？

至少评估：

### Relevance

证据是否真正涉及该 Need。

### Coverage

Need 是：

```text
covered / partial / missing
```

### Completeness

证据是否完整。

### Answerability

使用当前证据是否已经足以回答该部分问题。

### Progress

相比上一轮，本轮是否真正缩小了 Retrieval Gap。

### 推荐 Prompt

```text
你正在执行科研论文证据充分性判断。

你不能仅根据语义相似度判断证据是否有效。

请针对每个 Evidence Need 判断：
1. 当前证据是否真正与该 Need 相关；
2. 当前状态是 covered、partial 还是 missing；
3. 如果是 partial 或 missing，具体还缺少什么；
4. 当前证据是否足以让上层 Agent 在不虚构论文结论的情况下回答该 Need；
5. 与上一轮相比，新增加的证据是否实质性减少了该 Need 的信息缺口；
6. 哪些 Evidence Need 因本轮检索得到真实改善；
7. 当前是否已经解决回答用户问题所需的全部必要 Evidence Need。

判定标准：
- covered：证据已经足以可靠支撑该 Need；
- partial：证据真实有用并缩小了信息缺口，但仍不足以完整支撑；
- missing：没有能够实质支撑该 Need 的证据；
- 仅仅增加弱相关或重复内容，不算取得进展；
- 不得因为证据与问题“看起来相关”就判定为 covered。

输出结构化判断结果，不要生成最终用户答案。
```

---

## 24. Evidence Judge 推荐输出

```json
{
  "all_required_needs_resolved": false,

  "need_assessment": [
    {
      "need_id": "N1",
      "status": "covered",
      "evidence_ids": ["E2"]
    },
    {
      "need_id": "N2",
      "status": "covered",
      "evidence_ids": ["E2", "E3"]
    },
    {
      "need_id": "N3",
      "status": "partial",
      "evidence_ids": ["E7"],
      "gap": {
        "type": "missing_relationship",
        "description": "缺少信道时间相关性与状态设计之间的直接联系"
      }
    },
    {
      "need_id": "N4",
      "status": "missing",
      "evidence_ids": [],
      "gap": {
        "type": "missing_motivation",
        "description": "尚未找到作者对设计动机的说明"
      }
    }
  ],

  "unresolved_needs": [
    "N3",
    "N4"
  ],

  "round_progress": {
    "meaningful_progress": true,
    "improved_needs": [
      "N1",
      "N2",
      "N3"
    ]
  }
}
```

---

## 25. Evidence Item 数据结构

所有证据统一转换成：

```json
{
  "evidence_id": "E17",
  "paper_id": "P001",
  "source_unit_id": "SU102",

  "type": "paragraph",

  "content": "...",

  "location": {
    "page": 7,
    "bbox": [120, 220, 480, 360],
    "reading_order": 38
  },

  "object": {
    "label": null
  },

  "relations": [
    {
      "type": "refers_to",
      "target": "EQ12"
    }
  ],

  "retrieval": {
    "matched_need_ids": [
      "N2",
      "N3"
    ],
    "retrieval_score": 0.83,
    "rerank_score": 0.91
  }
}
```

该 EvidenceItem 是整条链路的统一数据对象：

```text
检索
↓
证据判断
↓
上下文注入
↓
答案引用
↓
高亮
↓
PDF 跳转
```

---

## 26. Structural Expansion

检索命中某个对象后，不应机械地只返回该对象。

例如：

```text
Equation 12
```

可能需要同时获得：

```text
前置说明段
Equation 12
后置解释段
变量定义
所属公式族
引用该公式的来源单元
```

因此推荐：

```text
Retrieve
→ Structural Expansion
→ Rerank
→ Evidence Judge
```

而不是：

```text
Vector Search → Top-K
```

---

## 27. Complete、Partial 与 Failed

### 27.1 COMPLETE

所有回答用户问题所需的核心 Evidence Need 均已解决：

```text
unresolved_needs = []
```

返回：

```text
retrieval_status = complete
```

### 27.2 PARTIAL

仍存在未解决 Evidence Need，但已经连续两轮检索没有取得有效推进，同时已经获得部分可靠证据。

返回：

```text
retrieval_status = partial
```

### 27.3 FAILED

连续两轮检索没有有效推进，并且没有任何足以支撑有用回答的可靠证据。

返回：

```text
retrieval_status = failed
```

---

## 28. Evidence Pack

推荐最终输出：

```json
{
  "retrieval_status": "complete",

  "question": "...",

  "evidence_needs": [
    {
      "id": "N1",
      "description": "...",
      "status": "covered"
    }
  ],

  "evidence": [
    {
      "evidence_id": "E3",
      "supports": ["N1"],
      "type": "paragraph",
      "content": "...",
      "paper_id": "P001",
      "source_unit_id": "SU102",
      "page": 7,
      "bbox": [120, 220, 480, 360],
      "reading_order": 38
    }
  ],

  "need_evidence_mapping": {
    "N1": ["E3"],
    "N2": ["E5", "E6"]
  },

  "unresolved_needs": [],

  "retrieval_state": {
    "rounds": 4,
    "failure_streak": 0
  }
}
```

---

## 29. Evidence Retrieval 与最终 Citation 分离

Skill 返回的是：

```text
Answer Evidence Pool
```

不是最终 Citation List。

上层 Agent 生成答案以后再建立：

```text
Claim → Evidence
```

关系。

例如：

```text
Claim C1 → E3
Claim C2 → E5, E6
Claim C3 → E9
```

最终 UI 只向用户展示实际支撑回答的 Evidence。

---

## 30. Direct Evidence 与 Inference

科研助手必须区分：

```text
Direct Evidence
```

和：

```text
Inference
```

例如论文明确存在：

```text
E1：状态包含 h(t-1)
E2：信道服从一阶 Markov 模型
```

但作者没有明确写：

```text
由于信道服从 Markov 模型，因此状态使用 h(t-1)。
```

那么上层 Agent 可以根据 E1、E2 推导该关系，但不能将其伪装成论文直接陈述。

推荐上层记录：

```json
{
  "claim_type": "inference",
  "based_on": ["E1", "E2"]
}
```

`paper-evidence` 负责找到基础证据，不负责把推断伪装为原文结论。

---

## 31. Skill 目录结构

建议：

```text
paper-evidence/
│
├── SKILL.md
│
├── references/
│   ├── evidence-schema.md
│   ├── retrieval-policy.md
│   └── evaluation-policy.md
│
└── scripts/
    ├── validate_plan.py
    ├── merge_evidence.py
    ├── deduplicate.py
    ├── compare_need_state.py
    └── validate_evidence_pack.py
```

---

## 32. Progressive Disclosure

### 第一层：Metadata

Agent 只看到：

```text
Skill 名称
Skill 用途
什么时候触发
什么时候不触发
```

### 第二层：SKILL.md

Skill 被触发后读取：

```text
核心流程
Evidence Need
检索原则
进展判断
连续两次失败退出
Evidence Pack
```

### 第三层：references

需要详细规则时再读取。

#### `evidence-schema.md`

定义：

```text
EvidenceNeed
EvidenceItem
EvidencePack
RetrievalState
```

#### `retrieval-policy.md`

定义：

```text
semantic search
exact lookup
keyword search
structural expansion
query rewrite
retrieval gap
```

#### `evaluation-policy.md`

定义：

```text
covered
partial
missing
meaningful progress
failed round
complete
partial result
```

### 第四层：scripts

确定性执行：

```text
Schema 校验
证据去重
Evidence Pool 合并
Need 状态比较
failure_streak 更新
Evidence Pack 校验
```

符合：

```text
Metadata
→ Core Instructions
→ Detailed References
→ Deterministic Utilities
```

的渐进披露结构。

---

## 33. 推荐的 `SKILL.md` 核心版本

```markdown
---
name: paper-evidence
description: >
  当回答用户问题必须依据已经解析并建立索引的科研论文内容时，使用本技能检索结构化证据。
  分析用户问题，识别回答所需的 Evidence Need，并围绕尚未解决的 Evidence Need 持续检索。
  每一轮都必须判断新增证据是否真实减少了未解决需求。
  只要检索持续取得有效进展，就继续检索；当所有必要 Evidence Need 均已解决时完成；
  当连续两轮检索没有产生有效进展时停止。
  返回证据时必须保留用于引用、PDF 高亮和页面跳转的来源定位信息。
---

# paper-evidence

## 适用范围

当回答用户问题必须依据已经解析并建立索引的当前科研论文内容时，使用本技能。

不要执行 PDF 解析、OCR、论文入库、文档切分、通用 Web 检索或跨论文证据搜索。

## 核心原则

检索的目标不是收集更多 Chunk，而是减少尚未解决的 Evidence Need。

只有当本轮新增证据使至少一个未解决 Evidence Need 得到实质性改善时，本轮检索才算成功。

## 工作流程

1. 分析用户问题。
2. 生成数量受控的 Evidence Need。
3. 将回答核心问题不可缺少的 Evidence Need 标记为 critical。
4. 初始化 unresolved Evidence Need 集合。
5. 只围绕 unresolved 或 partial 的 Evidence Need 规划下一轮检索。
6. 根据需要使用精确查找、关键词检索、语义检索和结构关系扩展。
7. 对结果进行重排、必要的上下文扩展和去重。
8. 判断每个 Evidence Need 当前属于 covered、partial 还是 missing。
9. 比较本轮与上一轮的 Evidence Need 状态。
10. 如果所有必要 Evidence Need 均已解决，返回 complete Evidence Pack。
11. 如果本轮取得有效进展，将连续失败计数清零，并继续检索剩余需求。
12. 如果本轮没有取得有效进展，将连续失败计数加一。
13. 当连续失败计数达到 2 时停止检索。
14. 如果已有部分可靠证据但仍存在未解决需求，返回 partial Evidence Pack。
15. 只有在没有得到足以支持任何有用回答的可靠证据时，返回 failed。

## Evidence Need 规则

Evidence Need 表示为了回答用户问题必须从论文中找到什么信息。

Evidence Need 不是检索关键词。

不得将未经证实的答案、因果关系或结论写进 Evidence Need。

Evidence Need 应保持数量精简、可以检索、可以判断、彼此尽量独立。

用户明确引用且回答必须依赖的公式、图、表、算法或章节，应当成为 critical Evidence Need。

检索过程中不得任意产生新的 Evidence Need。

只有当解决现有 Evidence Need 必须依赖论文内部的另一个对象时，才允许增加 dependency_need，并且必须记录 parent_need。

## 检索规则

只针对尚未解决或部分解决的 Evidence Need 主动检索。

已经 covered 的 Need 不再重复搜索，除非它是理解另一个未解决 Need 的必要依赖。

用户明确引用论文对象时，优先使用精确查找。

概念、机制、因果、方法、比较和解释类问题优先使用语义检索。

变量名、符号、缩写和精确术语需要使用关键词或符号检索。

当单个命中无法完整解释证据时，利用论文已有结构关系扩展上下文，包括相邻语义单元、引用关系、公式族、图表来源单元和算法来源单元。

不要重复发出语义等价且此前没有产生有效进展的查询。

## Progress 判断

每个 Evidence Need 必须被判定为：

- covered
- partial
- missing

以下变化可以构成候选进展：

- missing → partial
- missing → covered
- partial → covered

但只有当新增证据真实缩小该 Need 的 Retrieval Gap、提高回答该 Need 的能力时，才算 meaningful progress。

仅新增弱相关、重复或不能减少信息缺口的 Chunk，不算进展。

## 失败策略

维护连续失败计数 failure_streak。

如果本轮取得 meaningful progress：

    failure_streak = 0

如果本轮没有取得 meaningful progress：

    failure_streak += 1

当：

    failure_streak >= 2

立即停止检索。

不设置固定最大成功检索轮数。

只要仍有 unresolved Evidence Need 且每轮持续取得 meaningful progress，就继续检索。

## 完成条件

当回答用户问题所需要的 Evidence Need 均已得到充分解决时，检索完成。

相关证据不等于充分证据。

## 输出

返回 Evidence Pack，至少包含：

- retrieval_status
- evidence_needs
- evidence
- need_evidence_mapping
- unresolved_needs
- retrieval_state

每一个 Evidence Item 都必须保留用于后续引用、PDF 高亮和页面跳转的来源定位信息。

除非宿主工作流明确要求，否则本技能不直接生成最终用户回答。
```

---

## 34. Skill 与检索后端职责边界

`paper-evidence` 不关心底层采用：

```text
Elasticsearch
BM25
pgvector
Milvus
Qdrant
ColBERT
其他向量数据库或搜索服务
```

Skill 只依赖抽象检索能力：

```text
semantic_search()
keyword_search()
exact_lookup()
expand_context()
```

因此该 Skill 可以被不同 Agent、Codex 或不同论文检索后端复用。

---

## 35. 推荐职责分工

| 功能 | 实现方式 |
|---|---|
| Skill 触发 | 宿主 Agent |
| 显式 Eq/Fig/Table 等引用解析 | 确定性程序 |
| 用户问题语义理解 | LLM |
| Evidence Need 生成 | LLM |
| Evidence Need Schema 校验 | 确定性程序 |
| Query Planning | LLM |
| Semantic / Keyword / Exact Retrieval | 检索后端 |
| Rerank | Reranker |
| Structural Expansion | 确定性程序 |
| Evidence 去重与合并 | 确定性程序 |
| Need Coverage 判断 | LLM |
| Retrieval Gap 判断 | LLM |
| Meaningful Progress 判断 | LLM 输出 + 确定性状态比较 |
| failure_streak 更新 | 确定性程序 |
| 连续两次失败退出 | 确定性程序 |
| Evidence Pack 校验 | 确定性程序 |
| 最终回答 | 上层 Agent |
| Claim-Evidence Attribution | 上层 Agent |
| Highlight / PDF Jump | UI / 定位系统 |

---

## 36. 最终核心设计

整个 `paper-evidence` Skill 可以归纳为：

```text
paper-evidence
=
LLM Evidence Planning
+
Structured Retrieval
+
LLM Evidence Evaluation
+
Deterministic Progress Tracking
+
Two-Consecutive-Failure Exit
```

核心逻辑不是：

```text
Question
→ Search
→ Top-K
→ Answer
```

也不是：

```text
Search Twice
→ Stop
```

而是：

```text
Question
↓
Evidence Needs
↓
Unresolved Need Set
↓
Retrieve for Unresolved Needs
↓
Evaluate Evidence
↓
Did This Round Reduce the Unresolved Needs?
        │
        ├── Yes
        │    ↓
        │ failure_streak = 0
        │    ↓
        │ Continue
        │
        └── No
             ↓
          failure_streak += 1
             ↓
          failure_streak == 2 ?
             │
        ┌────┴────┐
       No         Yes
       ↓           ↓
    Continue      Stop
```

最终停止条件只有两种：

```text
1. 所有回答所需 Evidence Needs 已解决；

2. 连续两轮检索都没有产生有效 Evidence Progress。
```

因此，本 Skill 的核心评价对象不是“检索结果数量”，也不是单纯的“相关度”，而是：

> **本轮证据是否实质性减少了尚未解决的 Evidence Need。**

这应作为 `paper-evidence` Skill 后续实现、测试和评估的核心标准。
