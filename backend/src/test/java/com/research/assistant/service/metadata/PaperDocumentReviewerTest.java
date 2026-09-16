package com.research.assistant.service.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaperDocumentReviewerTest {

    private final PaperDocumentReviewer reviewer = new PaperDocumentReviewer();

    @Test
    void rejectsSinglePageDiagramLikeTextWithoutPaperStructure() {
        String diagramText = "Time Slot (Duration T) t Signal Transmission BS User Scheduling "
                + "CSI Reception Transmit Signal Design Vehicles Channel Estimation & Decoding "
                + "Signal Reception t CSI Feedback";

        PaperDocumentReviewer.Review result = reviewer.review(diagramText, diagramText);

        assertThat(result.status()).isEqualTo(PaperDocumentReviewer.Status.NOT_PAPER);
    }

    @Test
    void acceptsShortPaperWithTitleAuthorAbstractAndBody() {
        String paper = """
                Efficient Resource Allocation for Wireless Systems
                Alice Smith, Bob Jones
                Abstract—This paper presents a resource allocation method for reliable wireless
                communication and evaluates the method under several representative channels.
                1 Introduction
                We study the problem and formulate an optimization objective for the system.
                2 Method
                The proposed method alternates between scheduling and power allocation steps.
                3 Results
                Experiments show that the proposed method improves the target rate consistently.
                4 Conclusion
                We conclude with limitations and directions for future work.
                References
                """;

        PaperDocumentReviewer.Review result = reviewer.review(paper, paper);

        assertThat(result.status()).isEqualTo(PaperDocumentReviewer.Status.PAPER);
    }

    @Test
    void leavesTextlessScannedDocumentUncertain() {
        PaperDocumentReviewer.Review result = reviewer.review("", "");

        assertThat(result.status()).isEqualTo(PaperDocumentReviewer.Status.UNCERTAIN);
    }

    @Test
    void acceptsOlderPaperWithInitialsAndNoAbstractHeading() {
        String paper = """
                A Mathematical Theory of Communication
                C. E. Shannon
                INTRODUCTION
                The recent development of various methods of modulation has intensified the
                interest in a general theory of communication. A basis for such a theory is
                contained in earlier work, and the present paper extends it with new factors.
                The fundamental problem of communication is that of reproducing at one point
                either exactly or approximately a message selected at another point. This
                substantial body text continues with definitions, arguments, and examples.
                %s
                """.formatted("Communication systems and information measures. ".repeat(20));

        PaperDocumentReviewer.Review result = reviewer.review(paper, paper);

        assertThat(result.status()).isEqualTo(PaperDocumentReviewer.Status.PAPER);
    }
}
