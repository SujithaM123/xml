package com.project.toolkit.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * App-level configuration (see application.yml, prefix "app"). {@code allowedBaseFolders} is
 * the whitelist a server-side path (schema upload target, infer {@code serverPath} input) must
 * resolve inside -- the only thing standing between "read any file the server process can see"
 * and a legitimate feature, so every server-path-accepting endpoint must check it.
 */
@ConfigurationProperties(prefix = "app")
public final class AppProperties {

    private List<String> allowedBaseFolders = List.of();
    private String dataDir = "xml-toolkit-api-data";
    private long maxUploadBytes = 50L * 1024 * 1024;

    public List<Path> allowedBaseFolderPaths() {
        return allowedBaseFolders.stream().map(Path::of).map(Path::toAbsolutePath).map(Path::normalize).toList();
    }

    public List<String> getAllowedBaseFolders() {
        return allowedBaseFolders;
    }

    public void setAllowedBaseFolders(List<String> allowedBaseFolders) {
        this.allowedBaseFolders = allowedBaseFolders;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }
}
