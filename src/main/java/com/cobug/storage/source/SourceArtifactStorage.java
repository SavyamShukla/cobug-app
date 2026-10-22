package com.cobug.storage.source;

import java.io.IOException;
import java.util.UUID;

/**
 * Storage abstraction for source artifacts that should not live inside PostgreSQL.
 * PostgreSQL keeps the artifact metadata; the implementation owns the bytes. The current V1 implementation uses the local `storage` folder; it can later be replaced by Supabase/object storage.
 */
public interface SourceArtifactStorage {
    String put(UUID projectId, String sha256, byte[] bytes, String contentType) throws IOException;
    byte[] get(String storageKey) throws IOException;
    void delete(String storageKey) throws IOException;
    boolean isConfigured();
}
