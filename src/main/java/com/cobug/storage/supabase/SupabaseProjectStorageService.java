package com.cobug.storage.supabase;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import com.cobug.storage.ProjectStorageService;
import com.cobug.storage.StoredFileInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SupabaseProjectStorageService implements ProjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseProjectStorageService.class);

    private final SupabaseStorageClient client;
    private final ObjectMapper objectMapper;
    private final Map<String, List<StoredFileInfo>> inMemoryIndexCache = new ConcurrentHashMap<>();

    public SupabaseProjectStorageService(
            SupabaseStorageClient client,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    @Override
    public void initializeMain(Project project) throws IOException {
        String root = "main/" + project.getId();
        if (!inMemoryIndexCache.containsKey(root)) {
            writeMetadata(root, project, null, "main");
        }
    }

    @Override
    public void initializeWorkspace(Project project, User user) throws IOException {
        String root = "workspace/" + project.getId() + "/" + user.getId();
        if (!inMemoryIndexCache.containsKey(root)) {
            writeMetadata(root, project, user, "workspace");
        }
    }

    @Override
    public void saveMainFile(Project project, String relativePath, MultipartFile file) throws IOException {
        String cleanRel = cleanPath(relativePath);
        String path = cleanPath("main/" + project.getId() + "/files/" + cleanRel);
        client.upload(path, file.getBytes(), file.getContentType());
        updateIndex("main/" + project.getId(), cleanRel, file.getSize());
    }

    @Override
    public void saveMainFiles(Project project, List<String> relativePaths, List<MultipartFile> files) throws IOException {
        int count = Math.min(relativePaths.size(), files.size());
        if (count == 0) return;

        String rootPrefix = "main/" + project.getId();
        List<UploadTask> uploadTasks = new ArrayList<>();
        List<StoredFileInfo> batchItems = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String rel = cleanPath(relativePaths.get(i));
            MultipartFile file = files.get(i);
            if (rel.isBlank() || file == null || file.isEmpty()) continue;

            byte[] bytes = file.getBytes();
            String contentType = file.getContentType();
            String fullPath = cleanPath(rootPrefix + "/files/" + rel);
            String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;

            uploadTasks.add(new UploadTask(fullPath, bytes, contentType));
            batchItems.add(new StoredFileInfo(rel, name, "file", bytes.length, languageFromPath(name)));
        }

        executeParallelUploads(uploadTasks);
        updateBatchIndex(rootPrefix, batchItems);
        writeMetadata(rootPrefix, project, null, "main");
    }

    @Override
    public void saveWorkspaceFile(Project project, User user, String relativePath, MultipartFile file) throws IOException {
        String cleanRel = cleanPath(relativePath);
        String path = cleanPath("workspace/" + project.getId() + "/" + user.getId() + "/files/" + cleanRel);
        client.upload(path, file.getBytes(), file.getContentType());
        updateIndex("workspace/" + project.getId() + "/" + user.getId(), cleanRel, file.getSize());
    }

    @Override
    public void saveWorkspaceFiles(Project project, User user, List<String> relativePaths, List<MultipartFile> files) throws IOException {
        int count = Math.min(relativePaths.size(), files.size());
        if (count == 0) return;

        String rootPrefix = "workspace/" + project.getId() + "/" + user.getId();
        List<UploadTask> uploadTasks = new ArrayList<>();
        List<StoredFileInfo> batchItems = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String rel = cleanPath(relativePaths.get(i));
            MultipartFile file = files.get(i);
            if (rel.isBlank() || file == null || file.isEmpty()) continue;

            byte[] bytes = file.getBytes();
            String contentType = file.getContentType();
            String fullPath = cleanPath(rootPrefix + "/files/" + rel);
            String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;

            uploadTasks.add(new UploadTask(fullPath, bytes, contentType));
            batchItems.add(new StoredFileInfo(rel, name, "file", bytes.length, languageFromPath(name)));
        }

        executeParallelUploads(uploadTasks);
        updateBatchIndex(rootPrefix, batchItems);
        writeMetadata(rootPrefix, project, user, "workspace");
    }

    @Override
    public void saveWorkspaceFileBytes(Project project, User user, String relativePath, byte[] content) throws IOException {
        String cleanRel = cleanPath(relativePath);
        String path = cleanPath("workspace/" + project.getId() + "/" + user.getId() + "/files/" + cleanRel);
        byte[] bytes = content == null ? new byte[0] : content;
        client.upload(path, bytes, "application/octet-stream");
        updateIndex("workspace/" + project.getId() + "/" + user.getId(), cleanRel, bytes.length);
    }

    @Override
    public void copyMainFileToWorkspace(Project project, User user, String relativePath) throws IOException {
        String cleanRel = cleanPath(relativePath);
        String sourcePath = cleanPath("main/" + project.getId() + "/files/" + cleanRel);
        byte[] bytes = client.download(sourcePath);
        String destinationPath = cleanPath("workspace/" + project.getId() + "/" + user.getId() + "/files/" + cleanRel);
        client.upload(destinationPath, bytes, "application/octet-stream");
        updateIndex("workspace/" + project.getId() + "/" + user.getId(), cleanRel, bytes.length);
    }

    @Override
    public byte[] readMainFile(Project project, String relativePath) throws IOException {
        String path = cleanPath("main/" + project.getId() + "/files/" + relativePath);
        return client.download(path);
    }

    @Override
    public byte[] readWorkspaceFile(Project project, User user, String relativePath) throws IOException {
        String path = cleanPath("workspace/" + project.getId() + "/" + user.getId() + "/files/" + relativePath);
        return client.download(path);
    }

    @Override
    public List<StoredFileInfo> listMainFiles(Project project) throws IOException {
        String rootPrefix = "main/" + project.getId();
        return getOrFetchIndex(rootPrefix, rootPrefix + "/files");
    }

    @Override
    public List<StoredFileInfo> listWorkspaceFiles(Project project, User user) throws IOException {
        String rootPrefix = "workspace/" + project.getId() + "/" + user.getId();
        return getOrFetchIndex(rootPrefix, rootPrefix + "/files");
    }

    @Override
    public void deleteMainFile(Project project, String relativePath) throws IOException {
        String cleanRel = cleanPath(relativePath);
        String path = cleanPath("main/" + project.getId() + "/files/" + cleanRel);
        client.delete(path);
        removeFromIndex("main/" + project.getId(), cleanRel);
    }

    @Override
    public void deleteWorkspaceFile(Project project, User user, String relativePath) throws IOException {
        String cleanRel = cleanPath(relativePath);
        String path = cleanPath("workspace/" + project.getId() + "/" + user.getId() + "/files/" + cleanRel);
        client.delete(path);
        removeFromIndex("workspace/" + project.getId() + "/" + user.getId(), cleanRel);
    }

    @Override
    public void deleteProjectStorage(UUID projectId) throws IOException {
        if (projectId == null) return;
        inMemoryIndexCache.entrySet().removeIf(e -> e.getKey().contains(projectId.toString()));
        client.deletePrefixes(List.of(
                "main/" + projectId,
                "workspace/" + projectId,
                "source-artifacts/" + projectId,
                "project-files/" + projectId
        ));
    }

    @Override
    public void clearMainStorage(UUID projectId) throws IOException {
        if (projectId == null) return;
        inMemoryIndexCache.remove("main/" + projectId);
        client.deletePrefixes(List.of(
                "main/" + projectId,
                "source-artifacts/" + projectId
        ));
    }

    private List<StoredFileInfo> getOrFetchIndex(String rootPrefix, String filesPrefix) throws IOException {
        List<StoredFileInfo> cached = inMemoryIndexCache.get(rootPrefix);
        if (cached != null) return cached;

        List<StoredFileInfo> persistedIndex = readIndexFromStorage(rootPrefix);
        if (persistedIndex != null) {
            inMemoryIndexCache.put(rootPrefix, persistedIndex);
            return persistedIndex;
        }

        List<StoredFileInfo> discovered = toStoredFiles(client.listFilesRecursive(cleanPath(filesPrefix)));
        inMemoryIndexCache.put(rootPrefix, discovered);
        saveIndexToStorage(rootPrefix, discovered);
        return discovered;
    }

    private void updateIndex(String rootPrefix, String relativePath, long size) {
        List<StoredFileInfo> current = new ArrayList<>(inMemoryIndexCache.getOrDefault(rootPrefix, List.of()));
        Map<String, StoredFileInfo> map = new LinkedHashMap<>();
        for (StoredFileInfo item : current) map.put(item.path(), item);

        String name = relativePath.contains("/") ? relativePath.substring(relativePath.lastIndexOf('/') + 1) : relativePath;
        map.put(relativePath, new StoredFileInfo(relativePath, name, "file", size, languageFromPath(name)));

        List<StoredFileInfo> updated = new ArrayList<>(map.values());
        updated.sort(Comparator.comparing(StoredFileInfo::path, String.CASE_INSENSITIVE_ORDER));
        inMemoryIndexCache.put(rootPrefix, updated);
        saveIndexToStorage(rootPrefix, updated);
    }

    private void updateBatchIndex(String rootPrefix, List<StoredFileInfo> newItems) {
        List<StoredFileInfo> current = new ArrayList<>(inMemoryIndexCache.getOrDefault(rootPrefix, List.of()));
        Map<String, StoredFileInfo> map = new LinkedHashMap<>();
        for (StoredFileInfo item : current) map.put(item.path(), item);
        for (StoredFileInfo item : newItems) map.put(item.path(), item);

        List<StoredFileInfo> updated = new ArrayList<>(map.values());
        updated.sort(Comparator.comparing(StoredFileInfo::path, String.CASE_INSENSITIVE_ORDER));
        inMemoryIndexCache.put(rootPrefix, updated);
        saveIndexToStorage(rootPrefix, updated);
    }

    private void removeFromIndex(String rootPrefix, String relativePath) {
        List<StoredFileInfo> current = inMemoryIndexCache.get(rootPrefix);
        if (current == null) return;
        List<StoredFileInfo> updated = current.stream()
                .filter(item -> !item.path().equals(relativePath))
                .toList();
        inMemoryIndexCache.put(rootPrefix, updated);
        saveIndexToStorage(rootPrefix, updated);
    }

    private List<StoredFileInfo> readIndexFromStorage(String rootPrefix) {
        try {
            byte[] bytes = client.download(cleanPath(rootPrefix + "/files-index.json"));
            return objectMapper.readValue(bytes, new TypeReference<List<StoredFileInfo>>() {});
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (Exception e) {
            log.debug("[SUPABASE][STORAGE] Index read returned: {}", e.getMessage());
            return null;
        }
    }

    private void saveIndexToStorage(String rootPrefix, List<StoredFileInfo> files) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(files);
            client.upload(cleanPath(rootPrefix + "/files-index.json"), bytes, "application/json");
        } catch (Exception e) {
            log.warn("[SUPABASE][STORAGE] Non-critical index save notice for {}: {}", rootPrefix, e.getMessage());
        }
    }

    private void executeParallelUploads(List<UploadTask> tasks) throws IOException {
        if (tasks.isEmpty()) return;
        int threads = Math.min(16, Math.max(2, tasks.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>(tasks.size());

        for (UploadTask task : tasks) {
            futures.add(executor.submit(() -> {
                client.upload(task.path(), task.content(), task.contentType());
                return null;
            }));
        }

        executor.shutdown();
        try {
            for (Future<Void> future : futures) {
                future.get(90, TimeUnit.SECONDS);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Parallel Supabase upload failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Parallel Supabase upload interrupted", e);
        } catch (TimeoutException e) {
            throw new IOException("Parallel Supabase upload timed out", e);
        }
    }

    private List<StoredFileInfo> toStoredFiles(List<SupabaseStorageClient.SupabaseFileItem> items) {
        List<StoredFileInfo> list = new ArrayList<>();
        for (SupabaseStorageClient.SupabaseFileItem item : items) {
            String path = item.relativePath();
            if (path.equals("metadata.json") || path.equals("files-index.json")) continue;
            String name = item.name();
            list.add(new StoredFileInfo(
                    path,
                    name,
                    "file",
                    item.size(),
                    languageFromPath(name)
            ));
        }
        list.sort(Comparator.comparing(StoredFileInfo::path, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void writeMetadata(String rootPrefix, Project project, User user, String area) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("storageVersion", 1);
            metadata.put("storageProvider", "supabase");
            metadata.put("area", area);
            metadata.put("projectId", project.getId().toString());
            metadata.put("projectName", project.getName());
            metadata.put("ownerId", project.getOwner().getId().toString());
            metadata.put("ownerEmail", project.getOwner().getEmail());

            if (user != null) {
                metadata.put("userId", user.getId().toString());
                metadata.put("userEmail", user.getEmail());
            }

            metadata.put("updatedAt", new Date().toString());
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata);
            client.upload(cleanPath(rootPrefix + "/metadata.json"), bytes, "application/json");
        } catch (Exception e) {
            log.warn("[SUPABASE][STORAGE] Non-critical metadata write error for prefix {}: {}", rootPrefix, e.getMessage());
        }
    }

    private static String cleanPath(String raw) {
        return raw.replace('\\', '/').replaceAll("/+", "/").replaceAll("^/+", "");
    }

    private static String languageFromPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "text";
        return switch (path.substring(dot + 1).toLowerCase()) {
            case "java" -> "java";
            case "js" -> "javascript";
            case "jsx" -> "jsx";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "py" -> "python";
            case "c" -> "c";
            case "cpp" -> "cpp";
            case "h" -> "c";
            case "hpp" -> "cpp";
            case "html" -> "html";
            case "css" -> "css";
            case "json" -> "json";
            case "xml" -> "xml";
            case "yml", "yaml" -> "yaml";
            case "md" -> "markdown";
            case "sql" -> "sql";
            case "sh" -> "shell";
            case "bat" -> "bat";
            case "properties" -> "properties";
            default -> "text";
        };
    }

    private record UploadTask(
            String path,
            byte[] content,
            String contentType
    ) {}
}
