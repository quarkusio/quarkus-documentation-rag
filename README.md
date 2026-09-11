# Quarkus Documentation RAG

Tools for generating vector embeddings from Quarkus documentation and distributing them as SQL fragments for pgvector.

This project replaces the monolithic chappie-docling-rag pipeline with a distributed approach: each extension or documentation source produces its own `META-INF/quarkus-rag.sql` fragment, which is loaded into a generic pgvector database at runtime.

## Modules

- **quarkus-documentation-rag-core** — Core library: AsciiDoc processing (via AsciidoctorJ), semantic splitting, embedding generation (BGE Small EN v1.5, 384 dimensions), and SQL fragment output.
- **quarkus-documentation-rag-maven-plugin** — Maven plugin with two goals:
  - `generate-rag` — Process AsciiDoc guides and produce `META-INF/quarkus-rag.sql`
  - `aggregate-rag` — Collect multiple SQL fragments into one file

## Usage

### Directory mode (core Quarkus docs)

Process all guides in a directory. Each guide's source is derived from its `:extensions:` metadata:

```xml
<plugin>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-documentation-rag-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>generate-rag</goal></goals>
            <configuration>
                <guidesDirectory>${project.basedir}/docs/src/main/asciidoc</guidesDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Extension mode (Quarkiverse / third-party)

Process specific guides for a single extension. Typically configured in a parent POM — extensions opt in by setting one property:

```xml
<properties>
    <quarkus-rag.guide>docs/modules/ROOT/pages/index.adoc</quarkus-rag.guide>
</properties>
```

The plugin defaults `extensionName` to `${project.artifactId}` and silently skips modules where the property is not set.

### Build-supplied attributes

Guides whose include targets are built from attributes need those attributes passed to the parser:

```xml
<configuration>
    <attributes>
        <includes>${project.basedir}/docs/src/main/asciidoc/_includes</includes>
    </attributes>
</configuration>
```

Without this, `include::{includes}/attributes.adoc[]` cannot be resolved and its content is missing from the embeddings.

## How it works

1. AsciiDoc files are parsed using AsciidoctorJ's AST (no external service required). `include::` directives are resolved, attribute references are substituted where Asciidoctor would substitute them, and inline macros are reduced to their readable text
2. Documents are split into chunks at section boundaries using a semantic splitter. The preamble, between the document title and the first section heading, becomes a chunk of its own
3. Each chunk is embedded using the BGE Small EN v1.5 quantized ONNX model (384 dimensions)
4. The result is written as idempotent SQL: `DELETE` by source name + `INSERT` with vector embeddings and JSONB metadata

The SQL fragments are discovered at runtime by consumers (quarkus-agent-mcp, chappie-server) from extension deployment JARs or the aggregated `quarkus-documentation-core-rag` artifact.

## License

[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
