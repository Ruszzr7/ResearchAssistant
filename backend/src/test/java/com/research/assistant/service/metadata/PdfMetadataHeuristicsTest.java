package com.research.assistant.service.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfMetadataHeuristicsTest {

    private final PdfMetadataHeuristics extractor = new PdfMetadataHeuristics();

    @Test
    void shouldExtractTitleAndAbstractFromTypicalPdfText() {
        String text = """
                1518

                IEEE WIRELESS COMMUNICATIONS LETTERS, VOL. 13, NO. 5, MAY 2024

                Rate-Splitting Multiple Access With Finite Blocklength
                and High Mobility for URLLC Transmissions
                Jinping Zhu, Yingyang Chen, Member, IEEE

                Abstract—In this letter, we investigate the ergodic performance
                of rate-splitting multiple access in high mobility systems.
                Index Terms—Ergodic rate, finite blocklength.

                I. INTRODUCTION
                More text.
                """;

        PdfMetadataHeuristics.Metadata result = extractor.extract(text);

        assertEquals("Rate-Splitting Multiple Access With Finite Blocklength and High Mobility for URLLC Transmissions", result.title());
        assertEquals(2024, result.year());
        assertNotNull(result.abstractText());
        assertTrue(result.abstractText().startsWith("In this letter, we investigate"));
        assertTrue(result.abstractText().contains("high mobility systems."));
        assertEquals("Ergodic rate, finite blocklength", result.keywords());
    }

    @Test
    void shouldLimitLongAbstract() {
        String longAbstract = "word ".repeat(1000);
        PdfMetadataHeuristics.Metadata result = extractor.extract(
                "A Useful Paper Title\nAuthor Name\n\nAbstract: " + longAbstract + "\n\nIntroduction\nBody");

        assertNotNull(result.abstractText());
        assertTrue(result.abstractText().length() <= PdfMetadataHeuristics.MAX_ABSTRACT_LENGTH);
    }

    @Test
    void shouldStopAbstractBeforeInterleavedIntroductionHeading() {
        PdfMetadataHeuristics.Metadata result = extractor.extract(
                "A Useful Paper Title\nAuthor Name\n\n"
                        + "Abstract—The left column contains the complete abstract. I. INTRODUCTION "
                        + "The right column starts the first section.");

        assertEquals("The left column contains the complete abstract.", result.abstractText());
    }

    @Test
    void shouldExtractConferenceNameFromHeader() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                2024 IEEE/CVF Conference on Computer Vision and Pattern Recognition (CVPR)
                Seattle, Washington, June 17-21, 2024

                A Useful Conference Paper
                Alice Smith, Bob Jones

                Abstract—This paper presents a useful method.
                I. INTRODUCTION
                """);

        assertEquals("IEEE/CVF Conference on Computer Vision and Pattern Recognition (CVPR)", result.source());
        assertEquals(2024, result.year());
    }

    @Test
    void shouldExtractConferenceNameWhenPublicationLineIsWrapped() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                Proceedings of the 41st International Conference on
                Machine Learning (ICML 2024)

                A Useful Conference Paper
                Author Name

                Abstract: A short abstract.
                Introduction
                """);

        assertEquals("Proceedings of the 41st International Conference on Machine Learning (ICML 2024)", result.source());
        assertEquals(2024, result.year());
    }

    @Test
    void shouldJoinConferenceHeaderSplitAcrossLeftMarginLines() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                2024 IEEE
                International Conference on Communications
                in China (ICCC)

                A Useful Conference Paper
                Alice Smith, Bob Jones

                Abstract—This paper presents a useful method.
                I. INTRODUCTION
                """);

        assertEquals("IEEE International Conference on Communications in China (ICCC)", result.source());
        assertEquals(2024, result.year());
    }

    @Test
    void shouldExtractKeywordsWhenIndexTermsAreUsed() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                A Useful Conference Paper
                Alice Smith, Bob Jones

                Abstract—This paper presents a useful method.
                Index Terms—federated learning, edge intelligence, privacy.

                I. INTRODUCTION
                """);

        assertEquals("federated learning, edge intelligence, privacy", result.keywords());
    }

    @Test
    void shouldExtractConferenceAuthorsAndFooterSource() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                A Useful Conference Paper
                Alice Smith1, Bob Jones2, Carol Lee*
                1 University of Example, 2 Example Institute

                Abstract—This paper presents a useful method.
                I. INTRODUCTION
                The main text.

                2024 IEEE/CIC International Conference on Communications in China (ICCC)
                """);

        assertEquals("A Useful Conference Paper", result.title());
        assertEquals("Alice Smith, Bob Jones, Carol Lee", result.authors());
        assertEquals("IEEE/CIC International Conference on Communications in China (ICCC)", result.source());
        assertEquals(2024, result.year());
    }

    @Test
    void shouldReadRotatedIeeeConferencePublicationBlock() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                A Conference Paper
                Alice Smith, Bob Jones

                Abstract—A short abstract.
                Index Terms—Satellite-based Internet of Things, age of information,
                rate-splitting multiple access, short-packet communications,
                deep reinforcement learning.
                I. INTRODUCTION

                2023
                IEEE
                98th
                Vehicular
                Technology
                Conference
                (VTC2023-Fall)
                |
                979-8-3503-2928-5/23/$31.00
                ©2023 IEEE
                |
                DOI: 10.1109/VTC2023-Fall60731.2023.10333373
                """);

        assertEquals("IEEE 98th Vehicular Technology Conference (VTC2023-Fall)", result.source());
        assertEquals(2023, result.year());
        assertEquals("Satellite-based Internet of Things, age of information, rate-splitting multiple access, short-packet communications, deep reinforcement learning", result.keywords());
    }

    @Test
    void shouldReadPublicationAndYearWhenHeaderIsOnePipeSeparatedLine() {
        PdfMetadataHeuristics.Metadata result = extractor.extract("""
                A Conference Paper
                Alice Smith, Bob Jones

                Abstract—A short abstract.
                I. INTRODUCTION
                2024 19th International Symposium on Wireless Communication Systems (ISWCS) | 979-8-3503-6251-0/24/$31.00 ©2024 IEEE | DOI: 10.1109/ISWCS61526.2024.10639106
                """);

        assertEquals("19th International Symposium on Wireless Communication Systems (ISWCS)", result.source());
        assertEquals(2024, result.year());
    }
}
