package com.project.toolkit.generate;

import java.nio.file.Path;

/** Public, read-only summary of one XSD's identity -- for callers outside this package (the API
 * module's schema registry) that just need to know what a schema is, not generate from it. */
public final class SchemaInfo {

    public final String rootElementName;
    public final String namespace;

    private SchemaInfo(String rootElementName, String namespace) {
        this.rootElementName = rootElementName;
        this.namespace = namespace;
    }

    public static SchemaInfo read(Path xsd) {
        SchemaIndex index = new SchemaIndex(xsd);
        String ns = index.schema.getTargetNamespace();
        return new SchemaInfo(index.rootElement.getName(), ns == null ? "" : ns);
    }
}
