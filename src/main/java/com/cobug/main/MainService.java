package com.cobug.main;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
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
public class MainService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectStorageService storageService;
    private final ObjectMapper objectMapper;

    public MainService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            ProjectMemberRepository memberRepository,
            ProjectStorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    public MainResponse getMain(
            UUID projectId,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        verifyProjectAccess(project, user);

        try {
            storageService.initializeMain(project);

            List<MainFileResponse> files =
                    storageService.listMainFiles(project)
                            .stream()
                            .map(MainFileResponse::from)
                            .toList();

            return new MainResponse(
                    project.getId().toString(),
                    project.getName(),
                    project.getOwner().getId().toString(),
                    project.getOwner().getEmail(),
                    project.getName(),
                    files
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to read project Main storage.",
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

        verifyOwner(project, user);

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
            storageService.initializeMain(project);

            List<String> cleanPaths = new ArrayList<>();
            List<MultipartFile> validFiles = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                String path = normalizePath(paths.get(i));
                if (!path.isBlank()) {
                    cleanPaths.add(path);
                    validFiles.add(files[i]);
                }
            }

            storageService.saveMainFiles(project, cleanPaths, validFiles);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to save project files.",
                    e
            );
        }
    }

    /**
     * Copies files already stored in Main directly into the requesting user's
     * personal Workspace. No file bytes travel through the browser.
     */
    public int copyToWorkspace(
            UUID projectId,
            List<String> requestedPaths,
            String userEmail
    ) {

        Project project = getProject(projectId);
        User user = getUser(userEmail);

        // Main is owner-controlled, while the Workspace is personal to the
        // authenticated project member.
        verifyProjectAccess(project, user);

        if (requestedPaths == null || requestedPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one Main file is required.");
        }

        int copied = 0;

        try {
            storageService.initializeMain(project);
            storageService.initializeWorkspace(project, user);

            for (String requestedPath : requestedPaths) {
                String path = normalizePath(requestedPath);
                if (path.isBlank()) {
                    continue;
                }

                storageService.copyMainFileToWorkspace(
                        project,
                        user,
                        path
                );
                copied++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to copy Main files to Workspace.",
                    e
            );
        }

        return copied;
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
            return storageService.readMainFile(
                    project,
                    normalizePath(path)
            );
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Main file not found.",
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

        verifyOwner(project, user);

        try {
            storageService.deleteMainFile(
                    project,
                    normalizePath(path)
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to delete Main file.",
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

    private void verifyOwner(Project project, User user) {
        if (project.getOwner() == null
                || !project.getOwner().getId().equals(user.getId())) {

            throw new IllegalStateException(
                    "Only the project owner can access Main."
            );
        }
    }

    private void verifyProjectAccess(Project project, User user) {
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
