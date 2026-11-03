package com.project.toolkit.generate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes each file to its own path in a directory -- the original behavior, and still the
 * default. Safe to call concurrently: distinct file names mean distinct file handles. */
final class FolderOutputTarget implements OutputTarget {

    private final Path dir;

    FolderOutputTarget(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    @Override
    public void write(String fileName, String content) throws IOException {
        Files.writeString(dir.resolve(fileName), content);
    }

    @Override
    public void close() {
        // Nothing to release; each write already closed its own file handle.
    }
}
