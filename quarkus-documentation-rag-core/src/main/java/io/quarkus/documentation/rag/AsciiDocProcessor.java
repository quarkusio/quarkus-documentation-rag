package io.quarkus.documentation.rag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.Block;
import org.asciidoctor.ast.Cell;
import org.asciidoctor.ast.ContentNode;
import org.asciidoctor.ast.DescriptionList;
import org.asciidoctor.ast.DescriptionListEntry;
import org.asciidoctor.ast.Document;
import org.asciidoctor.ast.ListItem;
import org.asciidoctor.ast.Row;
import org.asciidoctor.ast.Section;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.ast.Table;

/**
 * Processes AsciiDoc files into structured sections using AsciidoctorJ's AST.
 * Uses raw source text (getSource/getLines) rather than getContent() to avoid
 * triggering JRuby HTML rendering, which is slow and can OOM on large docs.
 * <p>
 * Because raw source skips Asciidoctor's inline substitutions, this class applies
 * the two that matter for embedding quality itself: attribute references are
 * resolved against the document's attributes (only where Asciidoctor would have
 * resolved them, so verbatim blocks stay verbatim), and inline macros are reduced
 * to their human-readable text.
 */
public class AsciiDocProcessor implements AutoCloseable {

    private static final Pattern ATTRIBUTE_REF = Pattern.compile("\\{([a-zA-Z0-9_][a-zA-Z0-9_-]*)}");

    /** Asciidoctor's placeholder for an include it could not resolve. */
    private static final Pattern UNRESOLVED_INCLUDE =
            Pattern.compile("^Unresolved directive in .*? - include::.*$", Pattern.MULTILINE);

    private static final Pattern XREF_MACRO = Pattern.compile("xref:([^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern ANGLE_XREF = Pattern.compile("<<([^<>,]+)(?:,([^<>]+))?>>");
    private static final Pattern LINK_MACRO = Pattern.compile("link:([^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern URL_MACRO = Pattern.compile("(https?://[^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern IMAGE_MACRO = Pattern.compile("image::?([^\\[\\s]+)\\[([^\\]]*)]");
    private static final Pattern ICON_MACRO = Pattern.compile("icon:[^\\[\\s]+\\[[^\\]]*]");
    private static final Pattern UI_MACRO = Pattern.compile("(?:kbd|btn):\\[([^\\]]*)]");
    private static final Pattern MENU_MACRO = Pattern.compile("menu:([^\\[\\s]+)\\[([^\\]]*)]");

    private final Asciidoctor asciidoctor;
    private final Map<String, Object> attributes;

    public AsciiDocProcessor() {
        this(Map.of());
    }

    /**
     * @param attributes extra AsciiDoc attributes made available to the parser, for
     *        guides that rely on build-supplied attributes such as {@code {includes}}
     *        to resolve their include targets
     */
    public AsciiDocProcessor(Map<String, Object> attributes) {
        this.asciidoctor = Asciidoctor.Factory.create();
        this.attributes = Map.copyOf(attributes);
    }

    public ParsedDocument parse(Path adocPath) {
        Path baseDir = adocPath.toAbsolutePath().getParent();

        // UNSAFE is required for include:: to be honoured at all — AsciidoctorJ defaults
        // to SECURE, which silently turns every include into a link and drops its content.
        // The inputs here are documentation sources from the build itself, not user input.
        Document document = asciidoctor.loadFile(adocPath.toFile(),
                Options.builder()
                        .sourcemap(true)
                        .safe(SafeMode.UNSAFE)
                        .baseDir(baseDir.toFile())
                        .attributes(org.asciidoctor.Attributes.builder()
                                .attributes(attributes)
                                .build())
                        .build());

        String title = document.getDoctitle();
        Map<String, Object> documentAttributes = document.getAttributes();

        List<DocumentSection> sections = new ArrayList<>();
        addPreamble(document, title, documentAttributes, sections);
        extractSections(document, sections, new ArrayList<>(), documentAttributes);

        return new ParsedDocument(title, sections);
    }

    /**
     * Captures content between the document title and the first section heading.
     * It is usually the best short summary of what a guide is about, so it is worth
     * a chunk of its own rather than being dropped.
     */
    private void addPreamble(Document document, String title, Map<String, Object> attrs,
            List<DocumentSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (ContentNode child : document.getBlocks()) {
            if (child instanceof Section) {
                break;
            }
            if (child instanceof StructuralNode structuralBlock) {
                appendBlockContent(structuralBlock, sb, attrs);
            }
        }

        String content = sb.toString().trim();
        if (content.isBlank()) {
            return;
        }

        String preambleTitle = title != null ? title : "Introduction";
        sections.add(new DocumentSection(0, preambleTitle, content, preambleTitle));
    }

    private void extractSections(StructuralNode node, List<DocumentSection> sections, List<String> parentPath,
            Map<String, Object> attrs) {
        for (ContentNode child : node.getBlocks()) {
            if (child instanceof Section section) {
                List<String> currentPath = new ArrayList<>(parentPath);
                currentPath.add(section.getTitle());

                String content = renderSectionContent(section, attrs);
                if (!content.isBlank()) {
                    sections.add(new DocumentSection(
                            section.getLevel(),
                            section.getTitle(),
                            content,
                            String.join(" > ", currentPath)));
                }

                extractSections(section, sections, currentPath, attrs);
            }
        }
    }

    private String renderSectionContent(Section section, Map<String, Object> attrs) {
        StringBuilder sb = new StringBuilder();

        for (ContentNode block : section.getBlocks()) {
            if (block instanceof Section) {
                continue;
            }
            if (block instanceof StructuralNode structuralBlock) {
                appendBlockContent(structuralBlock, sb, attrs);
            }
        }

        return sb.toString().trim();
    }

    private void appendBlockContent(StructuralNode block, StringBuilder sb, Map<String, Object> attrs) {
        if (block instanceof Block b) {
            appendBlock(b, sb, attrs);
        } else if (block instanceof org.asciidoctor.ast.List list) {
            appendList(list, sb, attrs);
        } else if (block instanceof DescriptionList dlist) {
            appendDescriptionList(dlist, sb);
        } else if (block instanceof Table table) {
            appendTable(table, sb);
        } else {
            appendChildBlocks(block, sb, attrs);
        }
    }

    private void appendBlock(Block block, StringBuilder sb, Map<String, Object> attrs) {
        String context = block.getContext();

        switch (context) {
            case "listing", "literal" -> {
                String source = block.getSource();
                if (source != null && !source.isBlank()) {
                    // Verbatim blocks only resolve attributes when the author opts in
                    // with subs=attributes+, which getSubstitutions() reflects.
                    if (resolvesAttributes(block)) {
                        source = substituteAttributes(source, attrs);
                    }
                    sb.append("```").append(sourceLanguage(block)).append("\n")
                            .append(source).append("\n```\n\n");
                }
            }
            case "admonition" -> {
                String style = block.getStyle();
                String prefix = style != null ? style.toUpperCase() + ": " : "";
                String source = prose(block, attrs);
                if (!source.isBlank()) {
                    sb.append(prefix).append(source).append("\n\n");
                } else {
                    appendChildBlocks(block, sb, attrs);
                }
            }
            default -> {
                String source = prose(block, attrs);
                if (!source.isBlank()) {
                    sb.append(source).append("\n\n");
                } else {
                    appendChildBlocks(block, sb, attrs);
                }
            }
        }
    }

    /**
     * Returns the block's source as readable prose: attributes resolved, inline macros
     * reduced to their text, and Asciidoctor's unresolved-include placeholders removed
     * so they never reach the embedding.
     */
    private String prose(Block block, Map<String, Object> attrs) {
        String source = block.getSource();
        if (source == null || source.isBlank()) {
            return "";
        }
        source = UNRESOLVED_INCLUDE.matcher(source).replaceAll("");
        if (resolvesAttributes(block)) {
            source = substituteAttributes(source, attrs);
        }
        return stripInlineMacros(source).trim();
    }

    private static boolean resolvesAttributes(Block block) {
        java.util.List<String> subs = block.getSubstitutions();
        return subs == null || subs.isEmpty() || subs.contains("attributes");
    }

    private static String sourceLanguage(Block block) {
        Object language = block.getAttribute("language");
        return language != null ? language.toString() : "";
    }

    static String substituteAttributes(String text, Map<String, Object> attrs) {
        if (text.indexOf('{') < 0) {
            return text;
        }

        Matcher m = ATTRIBUTE_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = attrs.get(m.group(1).toLowerCase());
            // Unknown attributes are left as written, matching Asciidoctor's default
            // attribute-missing=skip rather than inventing a value.
            String replacement = value != null ? value.toString() : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Reduces inline macros to the text a reader would see. Raw macro syntax is noise
     * in an embedding vector and in the text handed back to the model at retrieval time.
     */
    static String stripInlineMacros(String text) {
        text = ICON_MACRO.matcher(text).replaceAll("");
        text = UI_MACRO.matcher(text).replaceAll("$1");
        text = MENU_MACRO.matcher(text).replaceAll(matchResult -> {
            String target = matchResult.group(1);
            String rest = matchResult.group(2);
            return Matcher.quoteReplacement(rest.isBlank() ? target : target + " > " + rest.replace(",", " > "));
        });
        text = IMAGE_MACRO.matcher(text).replaceAll(matchResult -> Matcher.quoteReplacement(
                firstNonBlank(matchResult.group(2), "")));
        text = XREF_MACRO.matcher(text).replaceAll(matchResult -> Matcher.quoteReplacement(
                firstNonBlank(matchResult.group(2), stripAnchor(matchResult.group(1)))));
        text = ANGLE_XREF.matcher(text).replaceAll(matchResult -> Matcher.quoteReplacement(
                firstNonBlank(matchResult.group(2), stripAnchor(matchResult.group(1)))));
        text = LINK_MACRO.matcher(text).replaceAll(matchResult -> Matcher.quoteReplacement(
                firstNonBlank(matchResult.group(2), matchResult.group(1))));
        text = URL_MACRO.matcher(text).replaceAll(matchResult -> Matcher.quoteReplacement(
                firstNonBlank(matchResult.group(2), matchResult.group(1))));
        // Dropped macros leave gaps behind, e.g. "click Save  to finish".
        return text.replaceAll("[ \\t]{2,}", " ");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred.trim() : fallback;
    }

    /** Turns {@code rest.adoc#section} or {@code rest.adoc} into a bare readable target. */
    private static String stripAnchor(String target) {
        String result = target;
        int hash = result.indexOf('#');
        if (hash >= 0) {
            result = hash == 0 ? result.substring(1) : result.substring(0, hash);
        }
        return result.replaceFirst("\\.adoc$", "");
    }

    private void appendList(org.asciidoctor.ast.List list, StringBuilder sb, Map<String, Object> attrs) {
        for (StructuralNode item : list.getItems()) {
            if (item instanceof ListItem li) {
                String text = li.getText();
                if (text != null && !text.isBlank()) {
                    sb.append("- ").append(stripHtml(text)).append("\n");
                }
                if (li.getBlocks() != null && !li.getBlocks().isEmpty()) {
                    for (ContentNode child : li.getBlocks()) {
                        if (child instanceof StructuralNode sn) {
                            appendBlockContent(sn, sb, attrs);
                        }
                    }
                }
            }
        }
        sb.append("\n");
    }

    private void appendDescriptionList(DescriptionList dlist, StringBuilder sb) {
        for (DescriptionListEntry entry : dlist.getItems()) {
            for (ListItem term : entry.getTerms()) {
                String text = term.getText();
                if (text != null) {
                    sb.append(stripHtml(text)).append(": ");
                }
            }
            ListItem desc = entry.getDescription();
            if (desc != null) {
                String text = desc.getText();
                if (text != null) {
                    sb.append(stripHtml(text));
                }
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    /**
     * Emits a GitHub-flavoured pipe table. Config reference tables are a common target
     * for "which property does X" queries, so the row/column structure has to survive.
     */
    private void appendTable(Table table, StringBuilder sb) {
        int columns = columnCount(table);
        if (columns == 0) {
            return;
        }

        List<Row> header = table.getHeader();
        for (Row row : header) {
            appendTableRow(row, columns, sb);
        }
        if (!header.isEmpty()) {
            sb.append("|").append(" --- |".repeat(columns)).append("\n");
        }
        for (Row row : table.getBody()) {
            appendTableRow(row, columns, sb);
        }
        sb.append("\n");
    }

    private int columnCount(Table table) {
        if (table.getColumns() != null && !table.getColumns().isEmpty()) {
            return table.getColumns().size();
        }
        int widest = 0;
        for (Row row : table.getHeader()) {
            widest = Math.max(widest, row.getCells().size());
        }
        for (Row row : table.getBody()) {
            widest = Math.max(widest, row.getCells().size());
        }
        return widest;
    }

    private void appendTableRow(Row row, int columns, StringBuilder sb) {
        List<Cell> cells = row.getCells();
        sb.append("|");
        for (int i = 0; i < columns; i++) {
            // Empty cells still get a column so the pipe table stays aligned.
            String text = i < cells.size() ? cellText(cells.get(i)) : "";
            sb.append(' ').append(text).append(" |");
        }
        sb.append("\n");
    }

    private String cellText(Cell cell) {
        String text = cell.getText();
        if (text == null) {
            return "";
        }
        return stripHtml(text).replace("|", "\\|").replaceAll("\\s*\n\\s*", " ");
    }

    private void appendChildBlocks(StructuralNode block, StringBuilder sb, Map<String, Object> attrs) {
        if (block.getBlocks() != null) {
            for (ContentNode nested : block.getBlocks()) {
                if (nested instanceof StructuralNode nestedBlock) {
                    appendBlockContent(nestedBlock, sb, attrs);
                }
            }
        }
    }

    static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    @Override
    public void close() {
        asciidoctor.close();
    }

    public record ParsedDocument(String title, List<DocumentSection> sections) {
    }

    public record DocumentSection(int level, String title, String content, String headerPath) {
    }
}
