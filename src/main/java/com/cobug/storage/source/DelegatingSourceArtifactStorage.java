package com.cobug.storage.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

@Service
@Primary
public class DelegatingSourceArtifactStorage implements SourceArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(DelegatingSourceArtifactStorage.class);

    private final LocalSourceArtifactStorage localService;
    private final SupabaseSourceArtifactStorage supabaseService;
    private final boolean preferSupabase;

    public DelegatingSourceArtifactStorage(
            LocalSourceArtifactStorage localService,
            SupabaseSourceArtifactStorage supabaseService,
            @Value("${cobug.storage.type:auto}") String storageType
    ) {
        this.localService = localService;
        this.supabaseService = supabaseService;
        this.preferSupabase = !"local".equalsIgnoreCase(storageType);

        if (preferSupabase && supabaseService.isConfigured()) {
            log.info("[COBUG][STORAGE] Active SourceArtifactStorage provider: SUPABASE (Cloud bucket)");
        } else {
            log.info("[COBUG][STORAGE] Active SourceArtifactStorage provider: LOCAL (Filesystem)");
        }
    }

    private SourceArtifactStorage delegate() {
        if (preferSupabase && supabaseService.isConfigured()) {
            return supabaseService;
        }
        return localService;
    }

    @Override
    public String put(UUID projectId, String sha256, byte[] bytes, String contentType) throws IOException {
        return delegate().put(projectId, sha256, bytes, contentType);
    }

    @Override
    public byte[] get(String storageKey) throws IOException {
        return delegate().get(storageKey);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        delegate().delete(storageKey);
    }

    @Override
    public boolean isConfigured() {
        return delegate().isConfigured();
    }
}
