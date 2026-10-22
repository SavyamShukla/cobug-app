package com.cobug.storage.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/**
 * Local object storage for large/binary Main source artifacts.
 * PostgreSQL stores metadata; the actual bytes are kept under the existing
 * COBUG storage directory. This is intentionally behind SourceArtifactStorage
 * so it can later be replaced by Supabase/object storage.
 */
@Service
public class LocalSourceArtifactStorage implements SourceArtifactStorage {
    private final Path root;

    public LocalSourceArtifactStorage(@Value("${cobug.storage.root:storage}") String storageRoot) {
        this.root = Paths.get(storageRoot).toAbsolutePath().normalize()
                .resolve("source-artifacts").normalize();
    }

    @Override
    public String put(UUID projectId, String sha256, byte[] bytes, String contentType) throws IOException {
        if (projectId == null || sha256 == null || sha256.isBlank()) {
            throw new IOException("Invalid source artifact identity.");
        }
        Path destination = safePath(projectId, sha256);
        Files.createDirectories(destination.getParent());
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, bytes == null ? new byte[0] : bytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return relativeKey(projectId, sha256);
    }

    @Override
    public byte[] get(String storageKey) throws IOException {
        Path file = resolveKey(storageKey);
        if (!Files.isRegularFile(file)) throw new NoSuchFileException(storageKey);
        return Files.readAllBytes(file);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        if (storageKey == null || storageKey.isBlank()) return;
        Files.deleteIfExists(resolveKey(storageKey));
    }

    @Override
    public boolean isConfigured() {
        try {
            Files.createDirectories(root);
            return Files.isDirectory(root) && Files.isWritable(root);
        } catch (IOException e) {
            return false;
        }
    }

    private Path safePath(UUID projectId, String sha256) throws IOException {
        if (!sha256.matches("[A-Fa-f0-9]{32,128}")) throw new IOException("Invalid source artifact hash.");
        return root.resolve(projectId.toString()).resolve(sha256).normalize();
    }

    private String relativeKey(UUID projectId, String sha256) {
        return "source-artifacts/" + projectId + "/" + sha256;
    }

    private Path resolveKey(String storageKey) throws IOException {
        String key = storageKey == null ? "" : storageKey.replace('\\', '/');
        if (key.startsWith("/")) throw new IOException("Invalid source storage key.");
        Path resolved = root.getParent().resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Invalid source storage key.");
        return resolved;
    }
}
