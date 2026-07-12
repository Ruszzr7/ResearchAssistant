package com.research.assistant.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSynthesisQualityGateTest {

    private final ResearchSynthesisQualityGate gate = new ResearchSynthesisQualityGate();

    @Test
    void shouldAcceptStructuredCompareReport() {
        String report = """
                ## Comparison

                | Dimension | Paper A | Paper B |
                |---|---|---|
                | Method | sparse | dense |
                | Dataset | long context | short context |
                | Performance | high recall | lower cost |

                The comparison explains the method and performance trade-off.
                """;

        assertTrue(gate.validateCompare(report, 2).valid());
    }

    @Test
    void shouldRejectShortCompareReportWithoutTable() {
        assertFalse(gate.validateCompare("Paper A is better than Paper B.", 2).valid());
    }

    @Test
    void shouldRequireThreeGapDimensionsAndHeadings() {
        String report = """
                ### [方法 Gap] Robustness
                需要进一步研究并验证新的方法方向。

                ### [场景 Gap] Long-tail data
                未来研究应覆盖真实场景并开展 research validation。

                ### [比较 Gap] Benchmark
                建议建立统一 benchmark 并验证比较结果。
                """;

        assertTrue(gate.validateGaps(report).valid());
        assertFalse(gate.validateGaps("### [方法 Gap] One\n需要研究。").valid());
    }
}
