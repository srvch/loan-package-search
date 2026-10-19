package com.infrrd.loanpackagesearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrrd.loanpackagesearch.model.PackageManifest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads package.json and tracks its last-modified time so callers can detect
 * edits made while the service is running (the manifest is re-read lazily,
 * on the next call to {@link #currentManifest()} after a change is seen).
 */
@Component
public class PackageRepository {

    private final Path dataDir;
    private final Path manifestPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<Loaded> loaded = new AtomicReference<>();

    public PackageRepository(@Value("${app.data-dir}") String dataDir) {
        this.dataDir = Path.of(dataDir);
        this.manifestPath = this.dataDir.resolve("package.json");
        reload();
    }

    private record Loaded(PackageManifest manifest, long mtimeMillis) {
    }

    public Path dataDir() {
        return dataDir;
    }

    /** True if package.json's mtime has moved on since we last loaded it. */
    public boolean isStale() {
        return currentMtime() != loaded.get().mtimeMillis();
    }

    /** Returns the manifest, reloading from disk first if package.json changed. */
    public synchronized PackageManifest currentManifest() {
        if (isStale()) {
            reload();
        }
        return loaded.get().manifest();
    }

    private long currentMtime() {
        try {
            return Files.getLastModifiedTime(manifestPath).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private synchronized void reload() {
        try {
            PackageManifest manifest = objectMapper.readValue(manifestPath.toFile(), PackageManifest.class);
            loaded.set(new Loaded(manifest, currentMtime()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load package.json at " + manifestPath, e);
        }
    }
}
