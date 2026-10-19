package com.localgov.service.payroll.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * {@link ObjectStorage} backed by a local directory. Writes go through
 * a temp file + atomic move so a crash mid-write never leaves a
 * partial PDF.
 */
@Component
public class FilesystemObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemObjectStorage.class);

    private final Path root;

    public FilesystemObjectStorage(
            @Value("${payroll.pdf.object-store-root:./var/payslips}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Could not create object storage root: " + this.root, e);
        }
        log.info("FilesystemObjectStorage root = {}", this.root);
    }

    @Override
    public String put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".pdf-", ".tmp");
            try {
                Files.write(tmp, content);
                Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store object at " + target, e);
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) return null;
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read object at " + target, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new IllegalArgumentException("Invalid object key: " + key);
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                "Object key escapes root: " + key + " -> " + resolved);
        }
        return resolved;
    }
}
