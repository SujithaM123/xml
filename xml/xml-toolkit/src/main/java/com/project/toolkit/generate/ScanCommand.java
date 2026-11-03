package com.project.toolkit.generate;

import java.nio.file.Path;

/** Public entry point for the `scan` CLI command -- wraps the package-private {@link SchemaIndex}
 * so callers outside this package (e.g. {@code ToolkitMain}) don't need their own XSD-loading. */
public final class ScanCommand {

    private ScanCommand() {
    }

    public static SchemaScanResult run(Path xsd) {
        SchemaIndex index = new SchemaIndex(xsd);
        return SchemaScanner.scan(index);
    }
}
