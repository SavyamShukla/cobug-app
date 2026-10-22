package com.cobug.storage;

import java.nio.file.Path;

public final class StoragePathUtil {

    private StoragePathUtil() {
    }

    public static Path safeResolve(Path root, String relativePath) {

        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path cannot be empty.");
        }

        String normalized = relativePath
                .replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+", "/")
                .trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("File path cannot be empty.");
        }

        Path resolved = root.resolve(normalized).normalize();

        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Invalid file path.");
        }

        return resolved;
    }
}
