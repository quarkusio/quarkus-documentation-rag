package io.quarkus.documentation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.documentation.rag.AsciiDocProcessor.DocumentSection;
import io.quarkus.documentation.rag.AsciiDocProcessor.ParsedDocument;

class AsciiDocProcessorTest {

    private static final Path TEST_GUIDE = Path.of("src/test/resources/test-guide.adoc");
    private static AsciiDocProcessor processor;

    @BeforeAll
    static void setUp() {
        processor = new AsciiDocProcessor();
    }

    @AfterAll
    static void tearDown() {
        processor.close();
    }

    @Test
    void parsesDocumentTitle() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);
        assertThat(doc.title()).isEqualTo("REST Guide");
    }

    @Test
    void capturesThePreambleBeforeTheFirstSection() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        assertThat(doc.sections().get(0).content()).isEqualTo("This is the preamble paragraph.");
    }

    @Test
    void extractsTopLevelSections() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        assertThat(doc.sections())
                .extracting(DocumentSection::title)
                .contains("Getting Started", "JSON Serialization", "Error Handling", "Testing");
    }

    @Test
    void extractsNestedSections() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        assertThat(doc.sections())
                .extracting(DocumentSection::title)
                .contains("Creating Your First Endpoint", "Running the Application");
    }

    @Test
    void buildsHeaderPaths() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        DocumentSection nested = doc.sections().stream()
                .filter(s -> s.title().equals("Creating Your First Endpoint"))
                .findFirst()
                .orElseThrow();

        assertThat(nested.headerPath()).isEqualTo("Getting Started > Creating Your First Endpoint");
    }

    @Test
    void sectionContentIncludesCodeBlocks() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        DocumentSection section = doc.sections().stream()
                .filter(s -> s.title().equals("Creating Your First Endpoint"))
                .findFirst()
                .orElseThrow();

        assertThat(section.content()).contains("@Path(\"/hello\")");
    }

    @Test
    void sectionContentIncludesAdmonitions() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        DocumentSection section = doc.sections().stream()
                .filter(s -> s.title().equals("Error Handling"))
                .findFirst()
                .orElseThrow();

        assertThat(section.content()).containsIgnoringCase("tip");
    }

    @Test
    void setsCorrectSectionLevels() {
        ParsedDocument doc = processor.parse(TEST_GUIDE);

        DocumentSection topLevel = doc.sections().stream()
                .filter(s -> s.title().equals("Getting Started"))
                .findFirst()
                .orElseThrow();
        assertThat(topLevel.level()).isEqualTo(1);

        DocumentSection nested = doc.sections().stream()
                .filter(s -> s.title().equals("Creating Your First Endpoint"))
                .findFirst()
                .orElseThrow();
        assertThat(nested.level()).isEqualTo(2);
    }
}
