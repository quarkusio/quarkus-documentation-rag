package io.quarkus.documentation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.documentation.rag.AsciiDocProcessor.DocumentSection;
import io.quarkus.documentation.rag.AsciiDocProcessor.ParsedDocument;

/**
 * Covers the extraction behaviour that determines what actually reaches the embedding:
 * resolved includes, resolved attributes, the preamble, table structure and inline macros.
 */
class AsciiDocExtractionFidelityTest {

    private static final Path GUIDE = Path.of("src/test/resources/includes/guide-with-includes.adoc");
    private static AsciiDocProcessor processor;
    private static ParsedDocument doc;

    @BeforeAll
    static void setUp() {
        processor = new AsciiDocProcessor();
        doc = processor.parse(GUIDE);
    }

    @AfterAll
    static void tearDown() {
        processor.close();
    }

    private static DocumentSection section(String title) {
        return doc.sections().stream()
                .filter(s -> s.title().equals(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No section titled " + title));
    }

    @Test
    void resolvesIncludedContent() {
        assertThat(section("Configuration").content())
                .contains("INCLUDED_SNIPPET_MARKER")
                .contains("quarkus.datasource.jdbc.max-size=16");
    }

    @Test
    void doesNotLeakIncludeDirectivesIntoContent() {
        assertThat(section("Configuration").content())
                .doesNotContain("include::")
                .doesNotContain("role=include");
    }

    @Test
    void dropsPlaceholdersForIncludesThatCannotBeResolved() {
        assertThat(doc.sections())
                .extracting(DocumentSection::content)
                .allSatisfy(content -> assertThat(content).doesNotContain("Unresolved directive"));
    }

    @Test
    void resolvesAttributesDefinedInAnIncludedFile() {
        assertThat(section("Configuration").content())
                .contains("The extension is stable")
                .doesNotContain("{extension-status}");
    }

    @Test
    void capturesThePreambleAsItsOwnSection() {
        DocumentSection preamble = doc.sections().get(0);

        assertThat(preamble.level()).isZero();
        assertThat(preamble.title()).isEqualTo("Datasource Guide");
        assertThat(preamble.content())
                .contains("PREAMBLE_MARKER")
                .contains("Quarkus 3.21.0");
    }

    @Test
    void leavesAttributesLiteralInVerbatimBlocksUnlessTheAuthorOptsIn() {
        String content = section("Verbatim Blocks").content();

        assertThat(content).contains("<version>{quarkus-version}</version>");
        assertThat(content).contains("<version>3.21.0</version>");
    }

    @Test
    void labelsSourceBlocksWithTheirLanguage() {
        assertThat(section("Verbatim Blocks").content()).contains("```xml");
        assertThat(section("Configuration").content()).contains("```properties");
    }

    @Test
    void rendersTablesAsPipeTables() {
        assertThat(section("Configuration").content())
                .contains("| Property | Default |")
                .contains("| --- | --- |")
                .contains("| quarkus.datasource.db-kind | none |");
    }

    @Test
    void keepsEmptyTableCellsSoColumnsStayAligned() {
        assertThat(section("Configuration").content())
                .contains("| quarkus.datasource.username |  |");
    }

    @Test
    void reducesInlineMacrosToTheirText() {
        String content = section("Inline Macros").content();

        assertThat(content).isEqualTo("""
                Press Ctrl+C then click Save to finish.
                See the guides and the section above.""");
    }

    @Test
    void substitutesKnownAttributesAndLeavesUnknownOnesAlone() {
        String result = AsciiDocProcessor.substituteAttributes(
                "Quarkus {quarkus-version} and {not-a-real-attribute}",
                java.util.Map.of("quarkus-version", "3.21.0"));

        assertThat(result).isEqualTo("Quarkus 3.21.0 and {not-a-real-attribute}");
    }

    @Test
    void stripsInlineAnchorsRoleMarksAndPassthroughs() {
        assertThat(AsciiDocProcessor.stripInlineMacros(
                "[[prop-id]] [.property-path]##`+++quarkus.foo+++`## and pass:[<b>] ##loud##"))
                .isEqualTo(" `quarkus.foo` and <b> loud");
    }

    @Test
    void fallsBackToTheTargetWhenACrossReferenceHasNoText() {
        assertThat(AsciiDocProcessor.stripInlineMacros("See <<json-config>> and icon:warning[] xref:rest.adoc[REST]."))
                .isEqualTo("See json-config and REST.");
    }
}
