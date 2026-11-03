package com.project.toolkit.generate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes every file as one entry in a ZIP archive, backed by whatever {@link OutputStream} the
 * caller gives it -- a file on disk for {@code --output zip}, or any other stream (a pipe, an
 * HTTP response body) for {@code --output stream}, which is the "same code serves files and
 * streaming" A10 asks for: both paths are this exact class, differing only in which stream backs
 * them. A single underlying {@link ZipOutputStream} isn't safe for concurrent entries, so writes
 * here are serialized -- generation itself still happens in parallel; only this final, comparatively
 * fast I/O step is single-threaded. */
final class ZipOutputTarget implements OutputTarget {

    private final ZipOutputStream zip;

    ZipOutputTarget(OutputStream out) {
        this.zip = new ZipOutputStream(out);
    }

    @Override
    public synchronized void write(String fileName, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(fileName));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
