package com.project.toolkit.api.security;

import com.project.toolkit.api.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Two things stand between "the user types a path" and "the server reads/writes any file it can
 * see": resolving a server-supplied path only inside a configured allow-list ({@link #resolve}),
 * and extracting an uploaded ZIP without letting an entry's name escape the destination
 * directory via {@code ../} segments or an absolute path ({@link #extractZipSafely}, the
 * "zip-slip" vulnerability).
 */
@Component
public final class PathSecurity {

    private final List<Path> allowedBases;
    private final long maxExtractedBytes;

    public PathSecurity(AppProperties properties) {
        this.allowedBases = properties.allowedBaseFolderPaths();
        this.maxExtractedBytes = properties.getMaxUploadBytes();
    }

    /** Resolves a user-supplied path string, requiring the result to land inside one of the
     * configured allowed base folders. Throws {@link SecurityException} otherwise -- including
     * for a path that merely LOOKS like it escapes (resolved and normalized first, so
     * {@code ../../etc/passwd}-style traversal is caught regardless of how it's spelled). */
    public Path resolve(String userSuppliedPath) {
        if (allowedBases.isEmpty()) {
            throw new SecurityException("No server paths are allowed: app.allowed-base-folders is empty");
        }
        Path candidate = Path.of(userSuppliedPath).toAbsolutePath().normalize();
        for (Path base : allowedBases) {
            if (candidate.equals(base) || candidate.startsWith(base)) {
                return candidate;
            }
        }
        throw new SecurityException("Path is outside the allowed server folders: " + userSuppliedPath);
    }

    public boolean isAllowed(Path path) {
        Path candidate = path.toAbsolutePath().normalize();
        return allowedBases.stream().anyMatch(base -> candidate.equals(base) || candidate.startsWith(base));
    }

    /** Extracts every entry of a ZIP into {@code destDir}, rejecting any entry whose resolved
     * path would land outside {@code destDir} (zip-slip), and capping total DECOMPRESSED bytes
     * at {@code app.max-upload-bytes} -- a small, highly-compressible upload can expand to a
     * huge amount of disk/memory on extraction (a "zip bomb"), which the servlet container's own
     * multipart size limit does nothing to stop since that only caps the compressed upload. */
    public void extractZipSafely(InputStream zipData, Path destDir) throws IOException {
        Path destRoot = destDir.toAbsolutePath().normalize();
        Files.createDirectories(destRoot);
        long totalBytes = 0;
        try (ZipInputStream zis = new ZipInputStream(zipData)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = destRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(destRoot)) {
                    throw new SecurityException("Zip entry escapes destination directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = zis.read(buffer)) >= 0) {
                        totalBytes += n;
                        if (totalBytes > maxExtractedBytes) {
                            throw new SecurityException("Zip would extract to more than the " + maxExtractedBytes
                                    + "-byte limit (app.max-upload-bytes) -- rejected as a likely zip bomb");
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }
}
