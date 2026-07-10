package com.research.assistant.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BibTeXExporterTest {

    private final BibTeXExporter exporter = new BibTeXExporter(new ObjectMapper());

    @Test
    void shouldExportArticleWithDoi() {
        Paper paper = new Paper();
        paper.setTitle("A Great Paper");
        paper.setAuthors("[{\"name\":\"Alice Smith\"},{\"name\":\"Bob Jones\"}]");
        paper.setYear(2024);
        paper.setSource("Nature");
        paper.setDoi("10.1038/s41586");

        String bib = exporter.export(paper);

        assertThat(bib).contains("@article{");
        assertThat(bib).contains("Smith2024Great");
        assertThat(bib).contains("title = {A Great Paper}");
        assertThat(bib).contains("author = {Alice Smith and Bob Jones}");
        assertThat(bib).contains("doi = {10.1038/s41586}");
    }

    @Test
    void shouldUseArxivWhenDoiMissing() {
        Paper paper = new Paper();
        paper.setTitle("ArXiv Paper");
        paper.setAuthors("[{\"name\":\"C Li\"}]");
        paper.setYear(2023);
        paper.setArxivId("2301.12345");

        String bib = exporter.export(paper);

        assertThat(bib).contains("eprint = {2301.12345}");
        assertThat(bib).contains("archivePrefix = {arXiv}");
    }

    @Test
    void shouldExportConferenceAsInproceedings() {
        Paper paper = new Paper();
        paper.setTitle("Conf Paper");
        paper.setAuthors("[{\"name\":\"D Wang\"}]");
        paper.setYear(2022);
        paper.setSource("NeurIPS Proceedings");

        String bib = exporter.export(paper);

        assertThat(bib).contains("@inproceedings{");
        assertThat(bib).contains("booktitle = {NeurIPS Proceedings}");
    }
}
