package io.quarkus.documentation.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.quarkus.documentation.rag.AsciiDocProcessor.DocumentSection;

/**
 * Splits AsciiDoc sections into chunks suitable for embedding.
 * Adapted from the Markdown-based MarkdownSemanticSplitter but works
 * directly with the AsciiDoc AST sections from {@link AsciiDocProcessor}.
 */
public class SemanticSplitter {

    private static final int MIN_SECTION_SIZE = 300;

    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|( --- \\|)+$");

    private final int maxChunkSize;

    public SemanticSplitter(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public List<Chunk> split(List<DocumentSection> sections) {
        if (sections.isEmpty()) {
            return List.of();
        }

        List<DocumentSection> merged = mergeSmallSections(sections);
        List<Chunk> chunks = new ArrayList<>();

        for (DocumentSection section : merged) {
            if (section.content().length() <= maxChunkSize) {
                chunks.add(new Chunk(
                        section.content(),
                        section.title(),
                        section.level(),
                        section.headerPath(),
                        null));
            } else {
                List<String> parts = splitLargeText(section.content());
                for (int i = 0; i < parts.size(); i++) {
                    chunks.add(new Chunk(
                            parts.get(i),
                            section.title(),
                            section.level(),
                            section.headerPath(),
                            (i + 1) + "/" + parts.size()));
                }
            }
        }

        return chunks;
    }

    private List<DocumentSection> mergeSmallSections(List<DocumentSection> sections) {
        List<DocumentSection> merged = new ArrayList<>();
        DocumentSection pending = null;

        for (DocumentSection current : sections) {
            if (pending == null) {
                pending = current;
                continue;
            }

            boolean shouldMerge = pending.content().length() < MIN_SECTION_SIZE;

            if (shouldMerge && (pending.level() <= 2 || current.level() <= 2)) {
                if (pending.content().length() >= 200) {
                    shouldMerge = false;
                }
            }

            if (shouldMerge && (pending.content().length() + current.content().length() > maxChunkSize)) {
                shouldMerge = false;
            }

            if (shouldMerge) {
                pending = mergeTwoSections(pending, current);
            } else {
                merged.add(pending);
                pending = current;
            }
        }

        if (pending != null) {
            merged.add(pending);
        }

        return merged;
    }

    private DocumentSection mergeTwoSections(DocumentSection first, DocumentSection second) {
        return new DocumentSection(
                first.level(),
                first.title() + " + " + second.title(),
                first.content() + "\n\n" + second.content(),
                first.headerPath() + " | " + second.headerPath());
    }

    private List<String> splitLargeText(String text) {
        List<String> parts = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (current.length() + paragraph.length() + 2 > maxChunkSize && !current.isEmpty()) {
                parts.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (paragraph.length() > maxChunkSize) {
                parts.addAll(splitOversizedParagraph(paragraph));
                continue;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }

        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }

        return parts;
    }

    /**
     * Splits a single block that is larger than a chunk on its own, which blank-line
     * splitting cannot do. Tables, such as config references, are split by rows with the
     * header repeated so every part is still a readable table. Code blocks are split by lines
     * and each part is fenced again. Anything else is split by lines. A single line longer
     * than a chunk is kept whole rather than cut mid-line.
     */
    private List<String> splitOversizedParagraph(String paragraph) {
        List<String> lines = List.of(paragraph.split("\n"));
        String prefix = "";
        String suffix = "";
        List<String> body = lines;

        if (lines.size() > 2 && lines.get(0).startsWith("```") && lines.get(lines.size() - 1).equals("```")) {
            prefix = lines.get(0) + "\n";
            suffix = "\n```";
            body = lines.subList(1, lines.size() - 1);
        } else if (lines.size() > 2 && lines.get(0).startsWith("|") && TABLE_SEPARATOR.matcher(lines.get(1)).matches()) {
            prefix = lines.get(0) + "\n" + lines.get(1) + "\n";
            body = lines.subList(2, lines.size());
        }

        List<String> parts = new ArrayList<>();
        int budget = maxChunkSize - prefix.length() - suffix.length();
        StringBuilder current = new StringBuilder();
        for (String line : body) {
            if (!current.isEmpty() && current.length() + line.length() + 1 > budget) {
                parts.add(prefix + current + suffix);
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append("\n");
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            parts.add(prefix + current + suffix);
        }
        return parts;
    }

    public record Chunk(String text, String sectionTitle, int sectionLevel, String sectionPath, String sectionPart) {
    }
}
