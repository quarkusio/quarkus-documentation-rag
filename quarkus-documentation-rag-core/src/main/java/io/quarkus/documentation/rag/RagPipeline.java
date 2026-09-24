package io.quarkus.documentation.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.quarkus.documentation.rag.AsciiDocProcessor.DocumentSection;
import io.quarkus.documentation.rag.AsciiDocProcessor.ParsedDocument;
import io.quarkus.documentation.rag.SemanticSplitter.Chunk;
import io.quarkus.documentation.rag.SqlFragmentWriter.ChunkWithEmbedding;

/**
 * Orchestrates the full RAG pipeline: parse AsciiDoc, split into chunks,
 * generate embeddings, and write SQL output.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Extension mode</b>: A fixed {@code extensionName} is set and applied to all guides.
 *       Used by Quarkiverse extensions and third-party libraries where docs belong to one extension.</li>
 *   <li><b>Directory mode</b>: No fixed extension name. Each guide derives its source identity
 *       from its own {@code :extensions:} metadata or filename. Used for core Quarkus where
 *       all docs live in one directory and each guide declares which extension(s) it covers.</li>
 * </ul>
 */
public class RagPipeline implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RagPipeline.class.getName());

    private final AsciiDocProcessor processor;
    private final SemanticSplitter splitter;
    private final EmbeddingGenerator embeddingGenerator;
    private final SqlFragmentWriter sqlWriter;

    private final String extensionName;
    private final String version;
    private final String guideBaseUrl;
    private final String guideUrl;

    public RagPipeline(String extensionName, String version, String guideBaseUrl, String guideUrl,
            int maxChunkSize) {
        this(extensionName, version, guideBaseUrl, guideUrl, maxChunkSize, Map.of());
    }

    /**
     * @param attributes extra AsciiDoc attributes for the parser, for guides whose
     *        include targets are built from build-supplied attributes
     */
    public RagPipeline(String extensionName, String version, String guideBaseUrl, String guideUrl,
            int maxChunkSize, Map<String, Object> attributes) {
        this.extensionName = extensionName;
        this.version = version;
        this.guideBaseUrl = guideBaseUrl;
        this.guideUrl = guideUrl;
        this.processor = new AsciiDocProcessor(attributes);
        this.splitter = new SemanticSplitter(maxChunkSize);
        this.embeddingGenerator = new EmbeddingGenerator();
        this.sqlWriter = new SqlFragmentWriter();
    }

    public void processGuides(List<Path> guides, Path outputPath) throws IOException {
        List<ChunkWithEmbedding> allChunks = new ArrayList<>();
        String sourceName = extensionName != null ? extensionName : "quarkus-documentation";

        for (Path guide : guides) {
            try {
                List<ChunkWithEmbedding> guideChunks = processGuide(guide);
                allChunks.addAll(guideChunks);
                LOG.info("Processed " + guide.getFileName() + ": " + guideChunks.size() + " chunks");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to process " + guide + ", skipping", e);
            }
        }

        sqlWriter.write(outputPath, sourceName, version, allChunks);
        LOG.info("Wrote " + allChunks.size() + " chunks to " + outputPath);
    }

    public void processDirectory(Path guidesDirectory, Path outputPath) throws IOException {
        List<Path> guides = findGuides(guidesDirectory);
        LOG.info("Found " + guides.size() + " guides in " + guidesDirectory);
        processGuides(guides, outputPath);
    }

    List<ChunkWithEmbedding> processGuide(Path guide) {
        Map<String, String> adocMetadata = AsciiDocMetadataExtractor.extractMetadata(guide);
        ParsedDocument doc = processor.parse(guide);
        List<DocumentSection> sections = doc.sections();
        List<Chunk> chunks = splitter.split(sections);

        String guideFileName = guide.getFileName().toString().replaceFirst("\\.adoc$", "");
        String url = guideUrl != null ? guideUrl
                : (guideBaseUrl != null ? guideBaseUrl + "/" + guideFileName : null);
        String resolvedSource = resolveSourceName(guideFileName, adocMetadata);

        List<ChunkWithEmbedding> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            float[] embedding = embeddingGenerator.embed(chunk.text());
            Map<String, String> metadata = SqlFragmentWriter.buildMetadata(
                    resolvedSource, version, doc.title(), url, adocMetadata, chunk);
            result.add(new ChunkWithEmbedding(chunk.text(), embedding, metadata));
        }

        return result;
    }

    /**
     * Determines the source name for a guide. In extension mode, uses the fixed
     * extensionName. In directory mode, derives it from the guide's :extensions:
     * metadata (first listed extension's artifactId) or falls back to the guide filename.
     */
    String resolveSourceName(String guideFileName, Map<String, String> adocMetadata) {
        if (extensionName != null) {
            return extensionName;
        }

        String extensions = adocMetadata.get("extensions");
        if (extensions != null && !extensions.isBlank()) {
            String firstExtension = extensions.split(",")[0].trim();
            if (firstExtension.contains(":")) {
                return firstExtension.substring(firstExtension.lastIndexOf(':') + 1).trim();
            }
            return firstExtension;
        }

        return "quarkus-" + guideFileName;
    }

    static List<Path> findGuides(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory, 1)) {
            return walk
                    .filter(p -> p.toString().endsWith(".adoc"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList();
        }
    }

    @Override
    public void close() {
        processor.close();
        embeddingGenerator.close();
    }
}
