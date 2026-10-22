package com.cobug.projects.files;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectFileService {

    private final ProjectFileRepository fileRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectFileService(
            ProjectFileRepository fileRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository
    ) {
        this.fileRepository = fileRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    // =========================================================
    // GET COMPLETE PROJECT TREE
    // =========================================================

    @Transactional(readOnly = true)
    public List<ProjectTreeNodeResponse> getProjectTree(
            UUID projectId,
            String userEmail
    ) {

        getOwnedProject(projectId, userEmail);

        List<ProjectFile> files =
                fileRepository.findProjectTree(projectId);

        return buildTree(files);
    }


    // =========================================================
    // CREATE FILE / FOLDER
    // =========================================================

    @Transactional
    public ProjectTreeNodeResponse create(
            UUID projectId,
            String userEmail,
            CreateProjectFileRequest request
    ) {

        Project project =
                getOwnedProject(projectId, userEmail);

        String path =
                normalizePath(request.path());

        validatePath(path);

        if (fileRepository.existsByProjectAndPath(
                projectId,
                path
        )) {

            throw new IllegalArgumentException(
                    "A file or folder already exists at: " + path
            );
        }

        /*
         * If a nested path is being created, make sure
         * the parent folder exists.
         *
         * Example:
         *
         * backend/src/Main.java
         *
         * requires:
         *
         * backend
         * backend/src
         */

        String parentPath =
                getParentPath(path);

        if (parentPath != null &&
                !fileRepository.existsByProjectAndPath(
                        projectId,
                        parentPath
                )) {

            throw new IllegalArgumentException(
                    "Parent folder does not exist: " + parentPath
            );
        }

        ProjectFile file =
                new ProjectFile();

        file.setProject(project);
        file.setPath(path);
        file.setName(getNameFromPath(path));
        file.setType(request.type());

        if (request.type() == ProjectFileType.FILE) {

            file.setContent(
                    request.content() == null
                            ? ""
                            : request.content()
            );

        } else {

            file.setContent(null);
        }

        ProjectFile saved =
                fileRepository.save(file);

        return toResponse(saved);
    }


    // =========================================================
    // GET SINGLE FILE
    // =========================================================

    @Transactional(readOnly = true)
    public ProjectTreeNodeResponse getFile(
            UUID projectId,
            String path,
            String userEmail
    ) {

        getOwnedProject(projectId, userEmail);

        String normalizedPath =
                normalizePath(path);

        validatePath(normalizedPath);

        ProjectFile file =
                fileRepository
                        .findByProjectAndPath(
                                projectId,
                                normalizedPath
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "File not found: "
                                                + normalizedPath
                                )
                        );

        return toResponse(file);
    }


    // =========================================================
    // UPDATE FILE CONTENT
    // =========================================================

    @Transactional
    public ProjectTreeNodeResponse update(
            UUID projectId,
            String path,
            String userEmail,
            UpdateProjectFileRequest request
    ) {

        getOwnedProject(projectId, userEmail);

        String normalizedPath =
                normalizePath(path);

        validatePath(normalizedPath);

        ProjectFile file =
                fileRepository
                        .findByProjectAndPath(
                                projectId,
                                normalizedPath
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "File not found: "
                                                + normalizedPath
                                )
                        );

        if (file.getType() != ProjectFileType.FILE) {

            throw new IllegalArgumentException(
                    "Cannot edit a folder"
            );
        }

        file.setContent(
                request.content() == null
                        ? ""
                        : request.content()
        );

        ProjectFile saved =
                fileRepository.save(file);

        return toResponse(saved);
    }


    // =========================================================
    // RENAME / MOVE
    // =========================================================

    @Transactional
    public ProjectTreeNodeResponse rename(
            UUID projectId,
            String oldPath,
            String userEmail,
            RenameProjectFileRequest request
    ) {

        getOwnedProject(projectId, userEmail);

        String source =
                normalizePath(oldPath);

        String destination =
                normalizePath(request.newPath());

        validatePath(source);
        validatePath(destination);

        ProjectFile sourceFile =
                fileRepository
                        .findByProjectAndPath(
                                projectId,
                                source
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "File or folder not found: "
                                                + source
                                )
                        );

        /*
         * Destination cannot already exist.
         */

        if (fileRepository.existsByProjectAndPath(
                projectId,
                destination
        )) {

            throw new IllegalArgumentException(
                    "Destination already exists: "
                            + destination
            );
        }

        /*
         * A folder cannot be moved inside itself.
         *
         * Example:
         *
         * backend
         * backend/src
         *
         * backend -> backend/src/new
         *
         * is invalid.
         */

        if (sourceFile.getType() == ProjectFileType.FOLDER) {

            if (destination.equals(source)
                    || destination.startsWith(source + "/")) {

                throw new IllegalArgumentException(
                        "A folder cannot be moved inside itself"
                );
            }
        }

        /*
         * Destination parent must exist if this is
         * a nested path.
         */

        String destinationParent =
                getParentPath(destination);

        if (destinationParent != null &&
                !fileRepository.existsByProjectAndPath(
                        projectId,
                        destinationParent
                )) {

            throw new IllegalArgumentException(
                    "Destination parent folder does not exist: "
                            + destinationParent
            );
        }

        /*
         * Get only the affected records.
         */

        List<ProjectFile> allFiles =
                fileRepository.findProjectTree(projectId);

        String sourcePrefix =
                source + "/";

        List<ProjectFile> affectedFiles =
                new ArrayList<>();

        for (ProjectFile file : allFiles) {

            if (file.getPath().equals(source)
                    || file.getPath().startsWith(sourcePrefix)) {

                affectedFiles.add(file);
            }
        }

        /*
         * Change only affected paths.
         */

        for (ProjectFile file : affectedFiles) {

            String currentPath =
                    file.getPath();

            String relativePath =
                    currentPath.equals(source)
                            ? ""
                            : currentPath.substring(
                                    source.length()
                            );

            String newPath =
                    destination + relativePath;

            file.setPath(newPath);
            file.setName(
                    getNameFromPath(newPath)
            );
        }

        /*
         * Save only affected records.
         */

        fileRepository.saveAll(affectedFiles);

        /*
         * Return the moved root item.
         */

        return toResponse(sourceFile);
    }


    // =========================================================
    // DELETE FILE / FOLDER
    // =========================================================

    @Transactional
    public void delete(
            UUID projectId,
            String path,
            String userEmail
    ) {

        getOwnedProject(projectId, userEmail);

        String normalizedPath =
                normalizePath(path);

        validatePath(normalizedPath);

        if (!fileRepository.existsByProjectAndPath(
                projectId,
                normalizedPath
        )) {

            throw new IllegalArgumentException(
                    "File or folder not found: "
                            + normalizedPath
            );
        }

        /*
         * Deletes the selected path and every descendant.
         */

        fileRepository.deletePathAndChildren(
                projectId,
                normalizedPath,
                normalizedPath + "/%"
        );
    }


    // =========================================================
    // OWNER ACCESS CHECK
    // =========================================================

    private Project getOwnedProject(
            UUID projectId,
            String userEmail
    ) {

        User currentUser =
                userRepository
                        .findByEmail(userEmail)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "User not found"
                                )
                        );

        Project project =
                projectRepository
                        .findById(projectId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Project not found"
                                )
                        );

        if (!project.getOwner()
                .getId()
                .equals(currentUser.getId())) {

            throw new IllegalArgumentException(
                    "Only the project owner can modify Main"
            );
        }

        return project;
    }


    // =========================================================
    // PATH NORMALIZATION
    // =========================================================

    private String normalizePath(String path) {

        if (path == null) {
            return "";
        }

        String normalized =
                path.trim()
                        .replace("\\", "/");

        while (normalized.startsWith("/")) {
            normalized =
                    normalized.substring(1);
        }

        while (normalized.endsWith("/")) {
            normalized =
                    normalized.substring(
                            0,
                            normalized.length() - 1
                    );
        }

        /*
         * Reject traversal attempts.
         */

        String[] parts =
                normalized.split("/");

        for (String part : parts) {

            if (part.equals("..")
                    || part.equals(".")) {

                throw new IllegalArgumentException(
                        "Invalid path"
                );
            }
        }

        return normalized;
    }


    private void validatePath(String path) {

        if (path == null || path.isBlank()) {

            throw new IllegalArgumentException(
                    "Path cannot be empty"
            );
        }

        if (path.length() > 1000) {

            throw new IllegalArgumentException(
                    "Path is too long"
            );
        }
    }


    // =========================================================
    // PATH HELPERS
    // =========================================================

    private String getNameFromPath(String path) {

        int index =
                path.lastIndexOf('/');

        if (index == -1) {
            return path;
        }

        return path.substring(index + 1);
    }


    private String getParentPath(String path) {

        int index =
                path.lastIndexOf('/');

        if (index == -1) {
            return null;
        }

        return path.substring(0, index);
    }


    // =========================================================
    // RESPONSE MAPPING
    // =========================================================

    private ProjectTreeNodeResponse toResponse(
            ProjectFile file
    ) {

        return new ProjectTreeNodeResponse(
                file.getId().toString(),
                file.getName(),
                file.getPath(),
                file.getType().name(),
                file.getType() == ProjectFileType.FILE
                        ? file.getContent()
                        : null,
                List.of()
        );
    }


    // =========================================================
    // BUILD TREE
    // =========================================================

    private List<ProjectTreeNodeResponse> buildTree(
            List<ProjectFile> files
    ) {

        /*
         * Tree construction is done in memory.
         *
         * Database remains flat:
         *
         * backend/src/Main.java
         * backend/src/utils/Test.java
         *
         * Frontend receives:
         *
         * backend
         *   +-- src
         *       +-- Main.java
         *       +-- utils
         *           +-- Test.java
         */

        List<MutableNode> roots =
                new ArrayList<>();

        for (ProjectFile file : files) {

            MutableNode node =
                    new MutableNode(
                            file.getId().toString(),
                            file.getName(),
                            file.getPath(),
                            file.getType().name(),
                            file.getContent()
                    );

            String parentPath =
                    getParentPath(file.getPath());

            if (parentPath == null) {

                roots.add(node);

            } else {

                MutableNode parent =
                        findNodeByPath(
                                roots,
                                parentPath
                        );

                if (parent != null) {

                    parent.children.add(node);

                } else {

                    /*
                     * This should normally not happen because
                     * nested creation requires parent folders.
                     *
                     * Keeping this fallback prevents the
                     * entire tree from disappearing if old
                     * data contains an orphaned path.
                     */

                    roots.add(node);
                }
            }
        }

        return roots.stream()
                .map(this::convertNode)
                .toList();
    }


    private MutableNode findNodeByPath(
            List<MutableNode> nodes,
            String path
    ) {

        for (MutableNode node : nodes) {

            if (node.path.equals(path)) {
                return node;
            }

            MutableNode found =
                    findNodeByPath(
                            node.children,
                            path
                    );

            if (found != null) {
                return found;
            }
        }

        return null;
    }


    private ProjectTreeNodeResponse convertNode(
            MutableNode node
    ) {

        return new ProjectTreeNodeResponse(
                node.id,
                node.name,
                node.path,
                node.type,
                node.type.equals("FILE")
                        ? node.content
                        : null,
                node.children.stream()
                        .map(this::convertNode)
                        .toList()
        );
    }


    private static class MutableNode {

        String id;
        String name;
        String path;
        String type;
        String content;

        List<MutableNode> children =
                new ArrayList<>();

        MutableNode(
                String id,
                String name,
                String path,
                String type,
                String content
        ) {

            this.id = id;
            this.name = name;
            this.path = path;
            this.type = type;
            this.content = content;
        }
    }
}
