package com.cobug.storage.source;

import com.cobug.storage.supabase.SupabaseStorageClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

@Service
public class SupabaseSourceArtifactStorage implements SourceArtifactStorage {

    private final SupabaseStorageClient client;

    public SupabaseSourceArtifactStorage(SupabaseStorageClient client) {
        this.client = client;
    }

    @Override
    public String put(UUID projectId, String sha256, byte[] bytes, String contentType) throws IOException {
        if (projectId == null || sha256 == null || sha256.isBlank()) {
            throw new IOException("Invalid source artifact identity.");
        }
        String key = "source-artifacts/" + projectId + "/" + sha256;
        client.upload(key, bytes, contentType);
        return key;
    }

    @Override
    public byte[] get(String storageKey) throws IOException {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IOException("Storage key cannot be empty.");
        }
        return client.download(storageKey);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        if (storageKey == null || storageKey.isBlank()) return;
        client.delete(storageKey);
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }
}
