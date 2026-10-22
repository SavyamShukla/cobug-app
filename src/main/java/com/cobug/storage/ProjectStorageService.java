package com.cobug.storage;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Storage abstraction for project source files.
 *
 * PostgreSQL stores project/application metadata; this service stores the
 * actual project files. The current implementation uses the local filesystem
 * and can later be replaced by a remote object-storage implementation.
 */
public interface ProjectStorageService {

    void initializeMain(Project project) throws IOException;

    void initializeWorkspace(Project project, User user) throws IOException;

    void saveMainFile(
            Project project,
            String relativePath,
            MultipartFile file
    ) throws IOException;

    default void saveMainFiles(
            Project project,
            List<String> relativePaths,
            List<MultipartFile> files
    ) throws IOException {
        int count = Math.min(relativePaths.size(), files.size());
        for (int i = 0; i < count; i++) {
            saveMainFile(project, relativePaths.get(i), files.get(i));
        }
    }

    void saveWorkspaceFile(
            Project project,
            User user,
            String relativePath,
            MultipartFile file
    ) throws IOException;

    default void saveWorkspaceFiles(
            Project project,
            User user,
            List<String> relativePaths,
            List<MultipartFile> files
    ) throws IOException {
        int count = Math.min(relativePaths.size(), files.size());
        for (int i = 0; i < count; i++) {
            saveWorkspaceFile(project, user, relativePaths.get(i), files.get(i));
        }
    }

    void saveWorkspaceFileBytes(
            Project project,
            User user,
            String relativePath,
            byte[] content
    ) throws IOException;

    /** Copy a file already stored in Main directly into a user's Workspace. */
    void copyMainFileToWorkspace(
            Project project,
            User user,
            String relativePath
    ) throws IOException;

    byte[] readMainFile(
            Project project,
            String relativePath
    ) throws IOException;

    byte[] readWorkspaceFile(
            Project project,
            User user,
            String relativePath
    ) throws IOException;

    List<StoredFileInfo> listMainFiles(Project project) throws IOException;

    List<StoredFileInfo> listWorkspaceFiles(
            Project project,
            User user
    ) throws IOException;

    void deleteMainFile(
            Project project,
            String relativePath
    ) throws IOException;

    void deleteWorkspaceFile(
            Project project,
            User user,
            String relativePath
    ) throws IOException;

    void deleteProjectStorage(UUID projectId) throws IOException;

    default void clearMainStorage(UUID projectId) throws IOException {}
}
