package io.quarkus.documentation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.documentation.rag.AsciiDocProcessor.DocumentSection;
import io.quarkus.documentation.rag.SemanticSplitter.Chunk;

class SemanticSplitterTest {

    @Test
    void splitsNormallySizedSections() {
        SemanticSplitter splitter = new SemanticSplitter(1000);

        List<DocumentSection> sections = List.of(
                new DocumentSection(1, "Intro", "A".repeat(500), "Intro"),
                new DocumentSection(1, "Setup", "B".repeat(400), "Setup"));

        List<Chunk> chunks = splitter.split(sections);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("Intro");
        assertThat(chunks.get(1).sectionTitle()).isEqualTo("Setup");
    }

    @Test
    void mergesSmallSections() {
        SemanticSplitter splitter = new SemanticSplitter(1000);

        List<DocumentSection> sections = List.of(
                new DocumentSection(3, "Tiny", "Small content", "Parent > Tiny"),
                new DocumentSection(3, "Also Tiny", "Also small", "Parent > Also Tiny"));

        List<Chunk> chunks = splitter.split(sections);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Small content");
        assertThat(chunks.get(0).text()).contains("Also small");
    }

    @Test
    void splitsLargeSectionsIntoParts() {
        SemanticSplitter splitter = new SemanticSplitter(200);

        String largeContent = ("Paragraph one about REST.\n\n" +
                "Paragraph two about JSON.\n\n" +
                "Paragraph three about testing.\n\n")
                .repeat(5);

        List<DocumentSection> sections = List.of(
                new DocumentSection(1, "Large", largeContent, "Large"));

        List<Chunk> chunks = splitter.split(sections);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).sectionPart()).isNotNull();
    }

    @Test
    void preservesMetadataInChunks() {
        SemanticSplitter splitter = new SemanticSplitter(1000);

        List<DocumentSection> sections = List.of(
                new DocumentSection(2, "My Section", "Content here.", "Parent > My Section"));

        List<Chunk> chunks = splitter.split(sections);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("My Section");
        assertThat(chunks.get(0).sectionLevel()).isEqualTo(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Parent > My Section");
        assertThat(chunks.get(0).sectionPart()).isNull();
    }

    @Test
    void returnsEmptyForEmptySections() {
        SemanticSplitter splitter = new SemanticSplitter(1000);
        List<Chunk> chunks = splitter.split(List.of());
        assertThat(chunks).isEmpty();
    }

    @Test
    void splitsOversizedTablesByRowsAndRepeatsTheHeader() {
        SemanticSplitter splitter = new SemanticSplitter(300);
        StringBuilder table = new StringBuilder("| Property | Default |\n| --- | --- |\n");
        for (int i = 0; i < 40; i++) {
            table.append("| quarkus.example.property-").append(i).append(" | value |\n");
        }

        List<Chunk> chunks = splitter.split(List.of(
                new DocumentSection(2, "Config", table.toString().trim(), "Config")));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(300);
            assertThat(chunk.text()).startsWith("| Property | Default |\n| --- | --- |\n| quarkus.example.property-");
        });
        assertThat(chunks.get(chunks.size() - 1).text()).contains("quarkus.example.property-39");
    }

    @Test
    void splitsOversizedCodeBlocksAndFencesEachPart() {
        SemanticSplitter splitter = new SemanticSplitter(300);
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 40; i++) {
            code.append("int value").append(i).append(" = ").append(i).append(";\n");
        }
        code.append("```");

        List<Chunk> chunks = splitter.split(List.of(
                new DocumentSection(2, "Code", code.toString(), "Code")));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(300);
            assertThat(chunk.text()).startsWith("```java\n").endsWith("\n```");
        });
    }
}
