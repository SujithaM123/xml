package com.project.toolkit.generate;

import java.io.IOException;

/**
 * Where a generated file's content goes -- a folder on disk, a ZIP archive, or an arbitrary
 * caller-supplied stream (A10). {@link GenerateCommand} writes through this interface uniformly;
 * validation uses {@link com.project.toolkit.validate.XsdValidatorCache#validateString} on the
 * in-memory content rather than reading back whatever {@link #write} did, so no implementation
 * needs to support being read from.
 */
interface OutputTarget extends AutoCloseable {

    void write(String fileName, String content) throws IOException;

    @Override
    void close() throws IOException;
}
