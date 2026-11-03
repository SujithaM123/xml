package com.project.toolkit.api;

import com.project.toolkit.api.config.AppProperties;
import com.project.toolkit.api.security.PathSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PathSecurityTest {

    @Test
    void rejectsAServerPathOutsideTheAllowedBaseFolders(@TempDir Path allowedDir, @TempDir Path otherDir) throws Exception {
        AppProperties props = new AppProperties();
        props.setAllowedBaseFolders(List.of(allowedDir.toString()));
        PathSecurity security = new PathSecurity(props);

        Path insideFile = allowedDir.resolve("ok.xml");
        Files.writeString(insideFile, "<a/>");
        assertEquals(insideFile.toAbsolutePath().normalize(), security.resolve(insideFile.toString()));

        Path outsideFile = otherDir.resolve("secret.xml");
        Files.writeString(outsideFile, "<a/>");
        assertThrows(SecurityException.class, () -> security.resolve(outsideFile.toString()));
    }

    @Test
    void rejectsDotDotTraversalEvenWhenSpelledToLookLikeItStaysInside(@TempDir Path allowedDir) throws Exception {
        AppProperties props = new AppProperties();
        props.setAllowedBaseFolders(List.of(allowedDir.resolve("sandbox").toString()));
        Files.createDirectories(allowedDir.resolve("sandbox"));
        PathSecurity security = new PathSecurity(props);

        String traversal = allowedDir.resolve("sandbox").resolve("..").resolve("outside.xml").toString();
        assertThrows(SecurityException.class, () -> security.resolve(traversal));
    }

    @Test
    void rejectsAZipEntryThatEscapesTheDestinationDirectory(@TempDir Path allowedDir, @TempDir Path destDir) throws Exception {
        AppProperties props = new AppProperties();
        props.setAllowedBaseFolders(List.of(allowedDir.toString()));
        PathSecurity security = new PathSecurity(props);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("../../evil.xml"));
            zos.write("<a/>".getBytes());
            zos.closeEntry();
        }

        SecurityException ex = assertThrows(SecurityException.class, () ->
                security.extractZipSafely(new ByteArrayInputStream(bos.toByteArray()), destDir));
        assertTrue(ex.getMessage().contains("escapes"));
    }

    @Test
    void rejectsAZipThatWouldExtractPastTheConfiguredByteLimit(@TempDir Path allowedDir, @TempDir Path destDir) throws Exception {
        AppProperties props = new AppProperties();
        props.setAllowedBaseFolders(List.of(allowedDir.toString()));
        props.setMaxUploadBytes(1000); // tiny limit so a normal-looking entry still trips it
        PathSecurity security = new PathSecurity(props);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("big.xml"));
            zos.write("x".repeat(5000).getBytes());
            zos.closeEntry();
        }

        SecurityException ex = assertThrows(SecurityException.class, () ->
                security.extractZipSafely(new ByteArrayInputStream(bos.toByteArray()), destDir));
        assertTrue(ex.getMessage().contains("zip bomb"));
    }

    @Test
    void extractsALegitimateZipNormally(@TempDir Path allowedDir, @TempDir Path destDir) throws Exception {
        AppProperties props = new AppProperties();
        props.setAllowedBaseFolders(List.of(allowedDir.toString()));
        PathSecurity security = new PathSecurity(props);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("sample1.xml"));
            zos.write("<a/>".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("subdir/sample2.xml"));
            zos.write("<b/>".getBytes());
            zos.closeEntry();
        }

        security.extractZipSafely(new ByteArrayInputStream(bos.toByteArray()), destDir);
        assertTrue(Files.exists(destDir.resolve("sample1.xml")));
        assertTrue(Files.exists(destDir.resolve("subdir/sample2.xml")));
    }
}
