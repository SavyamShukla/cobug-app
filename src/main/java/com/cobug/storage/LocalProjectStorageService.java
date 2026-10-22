package com.cobug.storage;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Service
public class LocalProjectStorageService implements ProjectStorageService {

    private final Path storageRoot;
    private final ObjectMapper objectMapper;

    public LocalProjectStorageService(
            @Value("${cobug.storage.root:storage}") String storageRoot,
            ObjectMapper objectMapper
    ) {
        this.storageRoot = Paths.get(storageRoot)
                .toAbsolutePath()
                .normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public void initializeMain(Project project) throws IOException {
        Path root = mainRoot(project.getId());
        Files.createDirectories(root.resolve("files"));
        writeMetadata(root, project, null, "main");
    }

    @Override
    public void initializeWorkspace(Project project, User user)
            throws IOException {

        Path root = workspaceRoot(project.getId(), user.getId());
        Files.createDirectories(root.resolve("files"));
        writeMetadata(root, project, user, "workspace");
    }

    @Override
    public void saveMainFile(
            Project project,
            String relativePath,
            MultipartFile file
    ) throws IOException {

        Path root = mainFilesRoot(project.getId());
        save(root, relativePath, file);
        writeMetadata(mainRoot(project.getId()), project, null, "main");
    }

    @Override
    public void saveMainFiles(
            Project project,
            List<String> relativePaths,
            List<MultipartFile> files
    ) throws IOException {
        Path root = mainFilesRoot(project.getId());
        int count = Math.min(relativePaths.size(), files.size());
        for (int i = 0; i < count; i++) {
            save(root, relativePaths.get(i), files.get(i));
        }
        writeMetadata(mainRoot(project.getId()), project, null, "main");
    }

    @Override
    public void saveWorkspaceFile(
            Project project,
            User user,
            String relativePath,
            MultipartFile file
    ) throws IOException {

        Path root = workspaceFilesRoot(project.getId(), user.getId());
        save(root, relativePath, file);
        writeMetadata(
                workspaceRoot(project.getId(), user.getId()),
                project,
                user,
                "workspace"
        );
    }

    @Override
    public void saveWorkspaceFiles(
            Project project,
            User user,
            List<String> relativePaths,
            List<MultipartFile> files
    ) throws IOException {
        Path root = workspaceFilesRoot(project.getId(), user.getId());
        int count = Math.min(relativePaths.size(), files.size());
        for (int i = 0; i < count; i++) {
            save(root, relativePaths.get(i), files.get(i));
        }
        writeMetadata(
                workspaceRoot(project.getId(), user.getId()),
                project,
                user,
                "workspace"
        );
    }

    private void save(
            Path root,
            String relativePath,
            MultipartFile file
    ) throws IOException {

        Files.createDirectories(root);

        Path destination = StoragePathUtil.safeResolve(
                root,
                relativePath
        );

        Path parent = destination.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (var input = file.getInputStream()) {
            Files.copy(
                    input,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    @Override
    public void copyMainFileToWorkspace(
            Project project,
            User user,
            String relativePath
    ) throws IOException {

        Path source = StoragePathUtil.safeResolve(
                mainFilesRoot(project.getId()),
                relativePath
        );

        if (!Files.isRegularFile(source)) {
            throw new NoSuchFileException(relativePath);
        }

        Path workspaceRoot = workspaceFilesRoot(
                project.getId(),
                user.getId()
        );
        Files.createDirectories(workspaceRoot);

        Path destination = StoragePathUtil.safeResolve(
                workspaceRoot,
                relativePath
        );

        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.copy(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING
        );

        writeMetadata(
                workspaceRoot(project.getId(), user.getId()),
                project,
                user,
                "workspace"
        );
    }

    @Override
    public void saveWorkspaceFileBytes(
            Project project,
            User user,
            String relativePath,
            byte[] content
    ) throws IOException {

        Path root = workspaceFilesRoot(project.getId(), user.getId());
        Files.createDirectories(root);

        Path destination = StoragePathUtil.safeResolve(
                root,
                relativePath
        );

        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(
                destination,
                content == null ? new byte[0] : content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        writeMetadata(
                workspaceRoot(project.getId(), user.getId()),
                project,
                user,
                "workspace"
        );
    }

    @Override
    public byte[] readMainFile(
            Project project,
            String relativePath
    ) throws IOException {

        return read(
                mainFilesRoot(project.getId()),
                relativePath
        );
    }

    @Override
    public byte[] readWorkspaceFile(
            Project project,
            User user,
            String relativePath
    ) throws IOException {

        return read(
                workspaceFilesRoot(project.getId(), user.getId()),
                relativePath
        );
    }

    private byte[] read(Path root, String relativePath)
            throws IOException {

        Path file = StoragePathUtil.safeResolve(root, relativePath);

        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(relativePath);
        }

        return Files.readAllBytes(file);
    }

    @Override
    public List<StoredFileInfo> listMainFiles(Project project)
            throws IOException {

        return list(mainFilesRoot(project.getId()));
    }

    @Override
    public List<StoredFileInfo> listWorkspaceFiles(
            Project project,
            User user
    ) throws IOException {

        return list(workspaceFilesRoot(project.getId(), user.getId()));
    }

    private List<StoredFileInfo> list(Path root) throws IOException {

        if (!Files.exists(root)) {
            return List.of();
        }

        List<StoredFileInfo> files = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            stream
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String relative = root
                                .relativize(path)
                                .toString()
                                .replace('\\', '/');

                        String name = path.getFileName().toString();

                        files.add(
                                new StoredFileInfo(
                                        relative,
                                        name,
                                        "file",
                                        safeSize(path),
                                        languageFromPath(name)
                                )
                        );
                    });
        }

        files.sort(Comparator.comparing(
                StoredFileInfo::path,
                String.CASE_INSENSITIVE_ORDER
        ));

        return files;
    }

    @Override
    public void deleteMainFile(
            Project project,
            String relativePath
    ) throws IOException {

        delete(mainFilesRoot(project.getId()), relativePath);
    }

    @Override
    public void deleteWorkspaceFile(
            Project project,
            User user,
            String relativePath
    ) throws IOException {

        delete(
                workspaceFilesRoot(project.getId(), user.getId()),
                relativePath
        );
    }

    private void delete(Path root, String relativePath)
            throws IOException {

        Path file = StoragePathUtil.safeResolve(root, relativePath);
        Files.deleteIfExists(file);

        cleanupEmptyParents(file.getParent(), root);
    }

    private void cleanupEmptyParents(Path current, Path root)
            throws IOException {

        while (current != null
                && !current.equals(root)
                && current.startsWith(root)) {

            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    break;
                }
            }

            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    @Override
    public void deleteProjectStorage(UUID projectId)
            throws IOException {

        Path projectMain = storageRoot
                .resolve("main")
                .resolve(projectId.toString())
                .normalize();

        Path projectWorkspace = storageRoot
                .resolve("workspace")
                .resolve(projectId.toString())
                .normalize();
        Path projectResources = storageRoot
                .resolve("project-files")
                .resolve(projectId.toString())
                .normalize();

        deleteDirectory(projectMain);
        deleteDirectory(projectWorkspace);
        deleteDirectory(projectResources);
    }

    @Override
    public void clearMainStorage(UUID projectId) throws IOException {
        if (projectId == null) return;
        deleteDirectory(mainRoot(projectId));
        deleteDirectory(storageRoot.resolve("source-artifacts").resolve(projectId.toString()));
    }

    private void deleteDirectory(Path directory)
            throws IOException {

        if (!Files.exists(directory)) {
            return;
        }

        if (!directory.startsWith(storageRoot)) {
            throw new IllegalArgumentException(
                    "Invalid storage path."
            );
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new StorageDeletionException(e);
                        }
                    });
        } catch (StorageDeletionException e) {
            throw e.ioException;
        }
    }

    private void writeMetadata(
            Path root,
            Project project,
            User user,
            String area
    ) throws IOException {

        Files.createDirectories(root);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("storageVersion", 1);
        metadata.put("area", area);
        metadata.put("projectId", project.getId().toString());
        metadata.put("projectName", project.getName());
        metadata.put("ownerId", project.getOwner().getId().toString());
        metadata.put("ownerEmail", project.getOwner().getEmail());

        if (user != null) {
            metadata.put("userId", user.getId().toString());
            metadata.put("userEmail", user.getEmail());
        }

        metadata.put("updatedAt", new Date());

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(
                        root.resolve("metadata.json").toFile(),
                        metadata
                );
    }

    private Path mainRoot(UUID projectId) {
        return storageRoot
                .resolve("main")
                .resolve(projectId.toString())
                .normalize();
    }

    private Path mainFilesRoot(UUID projectId) {
        return mainRoot(projectId).resolve("files").normalize();
    }

    private Path workspaceRoot(UUID projectId, UUID userId) {
        return storageRoot
                .resolve("workspace")
                .resolve(projectId.toString())
                .resolve(userId.toString())
                .normalize();
    }

    private Path workspaceFilesRoot(UUID projectId, UUID userId) {
        return workspaceRoot(projectId, userId)
                .resolve("files")
                .normalize();
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String languageFromPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "text";
        }

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

    private static class StorageDeletionException
            extends RuntimeException {

        private final IOException ioException;

        private StorageDeletionException(IOException cause) {
            super(cause);
            this.ioException = cause;
        }
    }
}
