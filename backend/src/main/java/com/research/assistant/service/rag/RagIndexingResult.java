package com.research.assistant.service.rag;

/** 成功建立一次论文索引后的确定性结果。 */
public record RagIndexingResult(Long paperId, boolean indexed, int chunkCount) {
}
