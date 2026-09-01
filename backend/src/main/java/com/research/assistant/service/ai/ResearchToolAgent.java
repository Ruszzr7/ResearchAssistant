package com.research.assistant.service.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import static com.research.assistant.service.ai.SuggestionPojos.*;

/**
 * 工具型科研 Agent 接口 —— 用于需要 LLM 自主调用工具但不需要对话记忆的任务。
 * <p>
 * 不绑定对话记忆，避免自动化任务受历史消息污染。
 */
public interface ResearchToolAgent {

    /**
     * 标签建议。
     */
    @SystemMessage("你是一位学术文献分类专家。请根据论文标题、摘要以及从用户论文库召回的相关片段，建议 3-5 个精准的技术关键词标签。")
    @UserMessage("""
            论文标题：{{title}}
            摘要：{{abstract}}

            用户论文库中相关片段：
            {{relatedSnippets}}

            请返回 JSON：{"tags":["tag1", "tag2", ...]}
            """)
    Result<TagSuggestionResult> suggestTags(@V("title") String title,
                                            @V("abstract") String abstractText,
                                            @V("relatedSnippets") String relatedSnippets);

    /**
     * 文件夹推荐。
     */
    @SystemMessage("""
            你是一位学术文献目录规划助手。请只使用论文标题、关键词、摘要和给出的目录树进行一次快速、可解释的归档决定。
            必须先判断论文是否与某个一级文件夹的核心主题相关；相关时再匹配该一级目录的二级目录，不相关时不得强行放入该一级目录。
            对二级目录，先识别论文的主题、场景、方法和主要优化指标。关键词和标题中出现的明确指标优先于摘要中的约束描述：例如论文研究 Ergodic Rate/Sum Rate，URLLC 或 low-latency 常可只是场景/约束，不能据此把论文归为 Latency。
            若一个一级目录下的现有子文件夹显然按“指标/场景/方法”之一分类，而论文没有匹配子目录，必须建议在该一级目录下新建同类型子目录；不要直接堆进父目录。
            """)
    @UserMessage("""
            论文标题：{{title}}
            关键词：{{keywords}}
            摘要：{{abstract}}

            现有文件夹列表：
            {{folders}}

            选择规则：先选择一级主题，再选择最深层匹配目录；只能从列表中的 id 选择，不要自行创建或改写 id。
            如果论文只匹配到一个已有父文件夹、但该父文件夹已经有子文件夹，而论文的具体方法/指标与现有子文件夹都不同，必须返回 folderId=null、suggestNew=true，newName 填主主题关键词，parentFolderId 填该父文件夹 ID；不要直接把论文放进这个父文件夹。
            若目录树说明该父目录的子项主要是指标，newName 必须是论文的主指标（如 Ergodic Rate），不能填仅作为约束或场景出现的 Latency、URLLC 等词。
            如果 suggestNew=true，newName 只填写要新建的文件夹名称；如果它是某个现有文件夹下的子文件夹，parentFolderId 填该现有文件夹 ID，根目录新建时填 null。
            请返回 JSON：{"folderId": 数字或null, "reason": "一句话理由", "suggestNew": true/false, "newName": "建议新文件夹名", "parentFolderId": 数字或null}
            """)
    Result<FolderSuggestionResult> suggestFolder(@V("title") String title,
                                                  @V("keywords") String keywords,
                                                  @V("abstract") String abstractText,
                                                  @V("folders") String folderList);

}
