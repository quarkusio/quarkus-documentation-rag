package io.quarkus.documentation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.documentation.rag.AsciiDocProcessor.DocumentSection;
import io.quarkus.documentation.rag.AsciiDocProcessor.ParsedDocument;

/**
 * Covers guides laid out like most Quarkiverse docs: a blank line after the title puts the
 * attributes include and later attribute entries in the body rather than the header, and
 * config reference tables use AsciiDoc-style cells.
 */
class AsciiDocBodyAttributesTest {

    private static final Path GUIDE = Path.of("src/test/resources/includes/guide-with-body-attributes.adoc");
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
    void resolvesAttributesFromAnIncludeBelowTheHeader() {
        assertThat(doc.sections().get(0).content()).contains("BODY_PREAMBLE_MARKER covers Quarkus 3.21.0.");
    }

    @Test
    void resolvesAttributesSetInTheBodyInsideAnIncludedVerbatimBlock() {
        assertThat(section("Dependency").content())
                .contains("<artifactId>quarkus-foo</artifactId>")
                .contains("<version>3.21.0</version>");
    }

    @Test
    void usesTheValueInEffectAtEachPointOfTheDocument() {
        assertThat(section("Redefined").content()).contains("Uses quarkus-bar");
    }

    @Test
    void resolvesBuiltInAttributes() {
        assertThat(section("Redefined").content()).contains("with spaces.");
    }

    @Test
    void rendersAsciiDocTableCellsAsReadableText() {
        String content = section("Config Reference").content();

        assertThat(content)
                .contains("| Configuration property | Type | Default |")
                .contains("`quarkus.foo.enabled`")
                .contains("Whether foo is enabled.")
                .contains("`QUARKUS_FOO_ENABLED`")
                .contains("| boolean | true |");
        assertThat(content)
                .doesNotContain("icon:")
                .doesNotContain("[[")
                .doesNotContain("link:")
                .doesNotContain("+++")
                .doesNotContain("##")
                .doesNotContain("[.")
                .doesNotContain("--");
    }
}
