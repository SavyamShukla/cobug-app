package com.cobug.workspace;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.activity.ProjectActivityService;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectRepository;
import com.cobug.projects.ProjectMemberRepository;
import com.cobug.storage.ProjectStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectStorageService storageService;
    private final ObjectMapper objectMapper;
    private final ProjectActivityService activityService;

    public WorkspaceService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            ProjectMemberRepository memberRepository,
            ProjectStorageService storageService,
            ObjectMapper objectMapper,
            ProjectActivityService activityService
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.activityService = activityService;
    }

    public WorkspaceResponse getWorkspace(
            UUID projectId,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        verifyProjectAccess(project, user);

        try {
            storageService.initializeWorkspace(project, user);

            List<WorkspaceFileResponse> files =
                    storageService.listWorkspaceFiles(project, user)
                            .stream()
                            .map(WorkspaceFileResponse::from)
                            .toList();

            return new WorkspaceResponse(
                    project.getId().toString(),
                    project.getName(),
                    user.getId().toString(),
                    user.getEmail(),
                    files
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to read workspace storage.",
                    e
            );
        }
    }

    public void importFiles(
            UUID projectId,
            String pathsJson,
            MultipartFile[] files,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        verifyProjectAccess(project, user);

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException(
                    "At least one file is required."
            );
        }

        List<String> paths = parsePaths(pathsJson);

        if (paths.size() != files.length) {
            throw new IllegalArgumentException(
                    "File paths and uploaded files do not match."
            );
        }

        try {
            storageService.initializeWorkspace(project, user);

            List<String> cleanPaths = new ArrayList<>();
            List<MultipartFile> validFiles = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                String path = normalizePath(paths.get(i));
                if (!path.isBlank()) {
                    cleanPaths.add(path);
                    validFiles.add(files[i]);
                }
            }

            storageService.saveWorkspaceFiles(project, user, cleanPaths, validFiles);
            activityService.record(projectId, user, "IMPORTED_WORKSPACE_FILES", "WORKSPACE", user.getId().toString(), "Imported " + validFiles.size() + " workspace file(s)");
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to save workspace files.",
                    e
            );
        }
    }

    public void saveFiles(
            UUID projectId,
            List<WorkspaceController.SaveWorkspaceFile> files,
            String userEmail
    ) {
        Project project = getProject(projectId);
        User user = getUser(userEmail);
        verifyProjectAccess(project, user);
        if (files == null) throw new IllegalArgumentException("Workspace files are required.");
        try {
            storageService.initializeWorkspace(project, user);
            for (WorkspaceController.SaveWorkspaceFile file : files) {
                if (file == null || normalizePath(file.path()).isBlank()) continue;
                storageService.saveWorkspaceFileBytes(
                        project, user, normalizePath(file.path()),
                        (file.content() == null ? "" : file.content()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );
            }
            activityService.record(projectId, user, "SAVED_WORKSPACE", "WORKSPACE", user.getId().toString(), "Saved personal Workspace changes");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save workspace files.", e);
        }
    }

    public byte[] readFile(
            UUID projectId,
            String path,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        verifyProjectAccess(project, user);

        try {
            return storageService.readWorkspaceFile(
                    project,
                    user,
                    normalizePath(path)
            );
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Workspace file not found.",
                    e
            );
        }
    }

    public void updateFile(
            UUID projectId,
            String path,
            byte[] content,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        verifyProjectAccess(project, user);

        try {
            storageService.saveWorkspaceFileBytes(
                    project,
                    user,
                    normalizePath(path),
                    content
            );
            activityService.record(projectId, user, "UPDATED_WORKSPACE_FILE", "WORKSPACE", normalizePath(path), "Updated workspace file " + normalizePath(path));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to save workspace file.",
                    e
            );
        }
    }

    public void deleteFile(
            UUID projectId,
            String path,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        verifyProjectAccess(project, user);

        try {
            storageService.deleteWorkspaceFile(
                    project,
                    user,
                    normalizePath(path)
            );
            activityService.record(projectId, user, "DELETED_WORKSPACE_FILE", "WORKSPACE", normalizePath(path), "Deleted workspace file " + normalizePath(path));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to delete workspace file.",
                    e
            );
        }
    }

    private List<String> parsePaths(String pathsJson) {
        if (pathsJson == null || pathsJson.isBlank()) {
            throw new IllegalArgumentException(
                    "File paths are required."
            );
        }

        try {
            return objectMapper.readValue(
                    pathsJson,
                    new TypeReference<List<String>>() {}
            );
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid file paths payload.",
                    e
            );
        }
    }

    private Project getProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Project not found."
                        )
                );
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "User not found."
                        )
                );
    }

    private void verifyProjectAccess(
            Project project,
            User user
    ) {
        boolean owner = project.getOwner() != null
                && project.getOwner().getId().equals(user.getId());

        if (owner) {
            return;
        }

        if (!memberRepository.existsByProjectIdAndUserId(
                project.getId(),
                user.getId()
        )) {
            throw new IllegalStateException(
                    "You do not have access to this project."
            );
        }
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }

        return path
                .replace("\\", "/")
                .replaceAll("^/+", "")
                .replaceAll("/+", "/")
                .trim();
    }
}
