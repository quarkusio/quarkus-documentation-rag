package io.quarkus.documentation.rag.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.quarkus.documentation.rag.RagPipeline;

/**
 * Processes AsciiDoc guides and generates a RAG SQL fragment at
 * META-INF/quarkus-rag.sql in the build output directory.
 * <p>
 * Three modes of operation:
 * <ul>
 *   <li><b>Extension mode</b>: Set {@code extensionName} and {@code guides} to process
 *       specific guides for a single extension (Quarkiverse, third-party libs).</li>
 *   <li><b>Directory mode</b>: Set {@code guidesDirectory} to scan an entire docs folder.
 *       Each guide derives its source from its own {@code :extensions:} metadata.
 *       Used for core Quarkus where all docs live in {@code docs/src/main/asciidoc/}.</li>
 *   <li><b>Parent POM mode</b>: The plugin is configured in a parent POM (e.g.,
 *       {@code quarkiverse-parent}). Extensions opt in by setting the
 *       {@code quarkus-rag.guide} property to their guide file path. Extensions
 *       without the property are silently skipped.</li>
 * </ul>
 */
@Mojo(name = "generate-rag", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class GenerateRagMojo extends AbstractMojo {

    /**
     * Individual guide files to process. Used in extension mode.
     * Mutually exclusive with {@code guidesDirectory}.
     */
    @Parameter
    private List<File> guides;

    /**
     * Directory containing .adoc files to process. Used in directory mode
     * for core Quarkus docs. Each guide's source identity is derived from
     * its :extensions: metadata or filename.
     * Mutually exclusive with {@code guides}.
     */
    @Parameter(property = "quarkus-rag.guidesDirectory")
    private File guidesDirectory;

    /**
     * Single guide file path, typically set via a property in child POMs.
     * This enables the parent POM pattern: the plugin is declared once in a parent,
     * and child modules opt in by setting {@code <quarkus-rag.guide>path/to/guide.adoc</quarkus-rag.guide>}.
     * If both {@code guide} and {@code guides} are set, they are merged.
     */
    @Parameter(property = "quarkus-rag.guide")
    private File guide;

    /**
     * Fixed extension/source name applied to all guides. Defaults to
     * {@code ${project.artifactId}} which works well for the parent POM pattern.
     * Optional in directory mode (where it's derived per-guide from metadata).
     */
    @Parameter(property = "quarkus-rag.extension-name", defaultValue = "${project.artifactId}")
    private String extensionName;

    @Parameter(property = "quarkus-rag.version", defaultValue = "${project.version}")
    private String version;

    @Parameter(property = "quarkus-rag.guideBaseUrl", defaultValue = "https://quarkus.io/guides")
    private String guideBaseUrl;

    /**
     * Full guide URL that overrides the constructed {@code guideBaseUrl + "/" + guideFileName}.
     * Use this for extensions whose docs live outside {@code quarkus.io/guides}, e.g.
     * {@code https://docs.quarkiverse.io/quarkus-vault/dev/index.html}.
     */
    @Parameter(property = "quarkus-rag.guideUrl")
    private String guideUrl;

    @Parameter(property = "quarkus-rag.outputFile", defaultValue = "${project.build.outputDirectory}/META-INF/quarkus-rag.sql")
    private File outputFile;

    @Parameter(defaultValue = "1000")
    private int maxChunkSize;

    /**
     * Extra AsciiDoc attributes passed to the parser. Needed by guides whose include
     * targets are built from build-supplied attributes, e.g.
     * {@code <includes>path/to/_includes</includes>} for {@code include::{includes}/attributes.adoc[]}.
     */
    @Parameter
    private Map<String, String> attributes;

    @Parameter(property = "quarkus-rag.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping RAG generation");
            return;
        }

        List<File> resolvedGuides = resolveGuides();
        boolean hasGuides = !resolvedGuides.isEmpty();
        boolean hasDirectory = guidesDirectory != null;

        // Silently skip if nothing is configured — this is the normal case
        // for modules that inherit the plugin from a parent POM but don't have docs
        if (!hasGuides && !hasDirectory) {
            getLog().debug("No guides or guidesDirectory configured — skipping RAG generation");
            return;
        }

        if (hasGuides && hasDirectory) {
            throw new MojoExecutionException("<guides>/<guide> and <guidesDirectory> are mutually exclusive");
        }

        // Strip -deployment suffix from extensionName (common when configured in deployment modules)
        String resolvedExtensionName = extensionName;
        if (resolvedExtensionName != null && resolvedExtensionName.endsWith("-deployment")) {
            resolvedExtensionName = resolvedExtensionName.substring(0,
                    resolvedExtensionName.length() - "-deployment".length());
        }

        try (RagPipeline pipeline = new RagPipeline(
                hasDirectory ? null : resolvedExtensionName,
                version, guideBaseUrl, guideUrl, maxChunkSize, resolveAttributes())) {
            if (hasDirectory) {
                getLog().info("Scanning directory " + guidesDirectory + " for guides (source from metadata)");
                pipeline.processDirectory(guidesDirectory.toPath(), outputFile.toPath());
            } else {
                List<Path> guidePaths = new ArrayList<>();
                for (File f : resolvedGuides) {
                    if (!f.exists()) {
                        getLog().warn("Guide file not found, skipping: " + f);
                        continue;
                    }
                    guidePaths.add(f.toPath());
                }
                if (guidePaths.isEmpty()) {
                    getLog().warn("No valid guide files found — skipping RAG generation");
                    return;
                }
                getLog().info("Generating RAG SQL for " + resolvedExtensionName + " from " + guidePaths.size() + " guide(s)");
                pipeline.processGuides(guidePaths, outputFile.toPath());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate RAG SQL", e);
        }

        getLog().info("RAG SQL written to " + outputFile);
    }

    private Map<String, Object> resolveAttributes() {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(attributes);
    }

    private List<File> resolveGuides() {
        List<File> resolved = new ArrayList<>();
        if (guides != null) {
            resolved.addAll(guides);
        }
        if (guide != null) {
            resolved.add(guide);
        }
        return resolved;
    }
}
