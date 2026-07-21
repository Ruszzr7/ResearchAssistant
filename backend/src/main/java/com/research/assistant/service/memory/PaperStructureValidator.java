package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ensures a derived structure never invents or rebinds fact-layer block identities. */
public final class PaperStructureValidator {

    private PaperStructureValidator() {
    }

    public static void validate(PaperStructure structure, PaperLayoutArtifact artifact) {
        if (structure == null || artifact == null) fail("结构或版面制品为空");
        if (!PaperStructure.SCHEMA_VERSION.equals(structure.schemaVersion())) fail("结构版本不匹配");
        if (!artifact.paperId().equals(structure.paperId())) fail("论文身份不匹配");
        if (!artifact.documentHash().equals(structure.source().documentHash())) fail("PDF 指纹不匹配");
        if (!artifact.parserVersion().equals(structure.source().layoutParserVersion())) fail("解析器版本不匹配");
        if (artifact.pageCount() != structure.pageCount()) fail("页数不匹配");

        Map<String, DocumentBlock> blocks = new HashMap<>();
        for (DocumentBlock block : artifact.blocks()) {
            if (blocks.put(block.id(), block) != null) fail("版面块 ID 重复");
        }
        requireUniqueKnown(structure.readingOrder(), blocks.keySet(), "阅读顺序");
        validatePages(structure.pages(), structure.pageCount(), blocks);

        for (PaperStructure.Section section : structure.sections()) {
            requireUniqueKnown(section.blockIds(), blocks.keySet(), "章节块引用");
            if (section.pageStart() < 1 || section.pageEnd() < section.pageStart()
                    || section.pageEnd() > structure.pageCount()) fail("章节页范围无效");
        }
        Set<String> elementIds = new HashSet<>();
        for (PaperStructure.Element element : structure.elements()) {
            if (!elementIds.add(element.id())) fail("结构元素 ID 重复");
            requireUniqueKnown(element.blockIds(), blocks.keySet(), "元素块引用");
            requireUniqueKnown(element.relatedBlockIds(), blocks.keySet(), "元素关联块引用");
            if (element.page() < 1 || element.page() > structure.pageCount()) fail("元素页码无效");
            for (String blockId : element.blockIds()) {
                if (blocks.get(blockId).page() != element.page()) fail("元素与来源块页码不一致");
            }
        }
        for (PaperStructure.CrossPageContinuation continuation : structure.crossPageContinuations()) {
            DocumentBlock from = blocks.get(continuation.fromBlockId());
            DocumentBlock to = blocks.get(continuation.toBlockId());
            if (from == null || to == null || from.page() != continuation.fromPage()
                    || to.page() != continuation.toPage()
                    || continuation.toPage() != continuation.fromPage() + 1) {
                fail("跨页关系无效");
            }
        }
    }

    private static void validatePages(List<PaperStructure.PageIndex> pages,
                                      int pageCount,
                                      Map<String, DocumentBlock> blocks) {
        if (pages.size() != pageCount) fail("页面索引不完整");
        Set<Integer> numbers = new HashSet<>();
        Set<String> indexedBlocks = new HashSet<>();
        for (PaperStructure.PageIndex page : pages) {
            if (page.page() < 1 || page.page() > pageCount || !numbers.add(page.page())) {
                fail("页面索引编号无效");
            }
            requireUniqueKnown(page.blockIds(), blocks.keySet(), "页面块引用");
            requireUniqueKnown(page.contentBlockIds(), new HashSet<>(page.blockIds()), "页面内容块引用");
            for (String blockId : page.blockIds()) {
                if (blocks.get(blockId).page() != page.page() || !indexedBlocks.add(blockId)) {
                    fail("页面块归属无效");
                }
            }
        }
        if (!indexedBlocks.equals(blocks.keySet())) fail("页面索引未覆盖全部版面块");
    }

    private static void requireUniqueKnown(List<String> ids, Set<String> known, String label) {
        Set<String> unique = new HashSet<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            if (!known.contains(id) || !unique.add(id)) fail(label + "无效");
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("论文结构完整性校验失败：" + message);
    }
}
