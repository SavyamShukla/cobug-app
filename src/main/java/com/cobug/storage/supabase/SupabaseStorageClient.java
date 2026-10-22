package com.cobug.storage.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.*;

@Component
public class SupabaseStorageClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageClient.class);

    private final String baseUrl;
    private final String serviceRoleKey;
    private final String bucket;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SupabaseStorageClient(
            @Value("${cobug.supabase.url:}") String url,
            @Value("${cobug.supabase.key:${cobug.supabase.service-role-key:}}") String serviceRoleKey,
            @Value("${cobug.supabase.bucket:cobug-storage}") String bucket,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = normalizeUrl(url);
        this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey.trim();
        this.bucket = (bucket == null || bucket.isBlank()) ? "cobug-storage" : bucket.trim();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        if (isConfigured()) {
            log.info("[SUPABASE][STORAGE] Configured with URL: {} and bucket: {}", this.baseUrl, this.bucket);
        } else {
            log.info("[SUPABASE][STORAGE] Not configured (missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY). Local storage fallback active.");
        }
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !serviceRoleKey.isBlank();
    }

    public String getBucket() {
        return bucket;
    }

    public void upload(String objectPath, byte[] content, String contentType) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Supabase storage is not configured. Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY.");
        }

        URI uri = objectUri(objectPath);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .header("Content-Type", contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream")
                .header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content == null ? new byte[0] : content))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("[SUPABASE][STORAGE] Upload failed status={} body={}", response.statusCode(), response.body());
                throw new IOException("Supabase upload failed (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Supabase upload interrupted for path: " + objectPath, e);
        }
    }

    public byte[] download(String objectPath) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Supabase storage is not configured.");
        }

        URI uri = authenticatedDownloadUri(objectPath);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                throw new NoSuchFileException(objectPath);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error = new String(response.body(), StandardCharsets.UTF_8);
                throw new IOException("Supabase download failed (HTTP " + response.statusCode() + "): " + error);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Supabase download interrupted for path: " + objectPath, e);
        }
    }

    public void delete(String objectPath) throws IOException {
        if (!isConfigured()) return;

        URI uri = objectUri(objectPath);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 404) {
                if (response.body() != null && (response.body().contains("not_found") || response.body().contains("NoSuchKey"))) {
                    return;
                }
                log.warn("[SUPABASE][STORAGE] Delete returned HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Supabase delete interrupted for path: " + objectPath, e);
        }
    }

    public void deletePrefixes(List<String> prefixes) throws IOException {
        if (!isConfigured() || prefixes == null || prefixes.isEmpty()) return;

        Set<String> allPaths = new LinkedHashSet<>();
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) continue;
            String clean = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
            allPaths.add(clean);
            allPaths.add(clean + "/files-index.json");
            try {
                List<SupabaseFileItem> items = listFilesRecursive(clean);
                for (SupabaseFileItem item : items) {
                    if (item.fullPath() != null && !item.fullPath().isBlank()) {
                        allPaths.add(item.fullPath());
                    }
                }
            } catch (Exception e) {
                log.warn("[SUPABASE][STORAGE] Error listing files for prefix {}: {}", clean, e.getMessage());
            }
        }

        if (allPaths.isEmpty()) return;

        List<String> pathList = new ArrayList<>(allPaths);
        for (int i = 0; i < pathList.size(); i += 100) {
            List<String> batch = pathList.subList(i, Math.min(i + 100, pathList.size()));
            URI uri = URI.create(baseUrl + "/storage/v1/object/" + bucket);
            Map<String, Object> bodyMap = Map.of("prefixes", batch);
            String json = objectMapper.writeValueAsString(bodyMap);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .header("apikey", serviceRoleKey)
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(json))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                log.info("[SUPABASE][STORAGE] deletePrefixes batch size={} returned HTTP {}", batch.size(), response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Supabase deletePrefixes interrupted", e);
            }
        }
    }

    public List<SupabaseFileItem> listFilesRecursive(String basePrefix) throws IOException {
        if (!isConfigured()) return List.of();
        List<SupabaseFileItem> results = new ArrayList<>();
        collectFiles(basePrefix, basePrefix, results);
        return results;
    }

    private void collectFiles(String currentPrefix, String rootPrefix, List<SupabaseFileItem> accumulator) throws IOException {
        URI uri = URI.create(baseUrl + "/storage/v1/object/list/" + bucket);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prefix", currentPrefix);
        payload.put("limit", 1000);
        payload.put("offset", 0);
        payload.put("sortBy", Map.of("column", "name", "order", "asc"));

        String jsonPayload = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[SUPABASE][STORAGE] List returned status={}: {}", response.statusCode(), response.body());
                return;
            }

            JsonNode array = objectMapper.readTree(response.body());
            if (array == null || !array.isArray()) return;

            for (JsonNode item : array) {
                String name = item.path("name").asText("");
                if (name.isBlank() || name.equals(".emptyFolderPlaceholder")) continue;

                boolean isFolder = item.path("id").isNull() || item.path("id").asText("").isBlank();
                String itemFullPath = currentPrefix.isEmpty() ? name : (currentPrefix.endsWith("/") ? currentPrefix + name : currentPrefix + "/" + name);

                if (isFolder) {
                    collectFiles(itemFullPath, rootPrefix, accumulator);
                } else {
                    long size = item.path("metadata").path("size").asLong(0L);
                    String relativeToRoot = itemFullPath.startsWith(rootPrefix)
                            ? itemFullPath.substring(rootPrefix.length()).replaceAll("^/+", "")
                            : itemFullPath;

                    accumulator.add(new SupabaseFileItem(
                            itemFullPath,
                            relativeToRoot,
                            name,
                            size
                    ));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Supabase list interrupted for prefix: " + currentPrefix, e);
        }
    }

    private URI objectUri(String objectPath) {
        return URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + encodePath(objectPath));
    }

    private URI authenticatedDownloadUri(String objectPath) {
        return URI.create(baseUrl + "/storage/v1/object/authenticated/" + bucket + "/" + encodePath(objectPath));
    }

    private static String encodePath(String path) {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = clean.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("/");
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record SupabaseFileItem(
            String fullPath,
            String relativePath,
            String name,
            long size
    ) {}
}
