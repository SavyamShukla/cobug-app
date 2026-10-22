package com.cobug.projects;

import com.cobug.auth.UserRepository;
import com.cobug.activity.ProjectActivityService;
import com.cobug.storage.ProjectStorageService;
import com.cobug.reviews.ReviewCleanupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectManagementController {

    private final UserRepository userRepository;
    private final ProjectStorageService storageService;
    private final ProjectActivityService activityService;
    private final ReviewCleanupService reviewCleanupService;

    @PersistenceContext
    private EntityManager entityManager;

    public ProjectManagementController(
            UserRepository userRepository,
            ProjectStorageService storageService,
            ProjectActivityService activityService,
            ReviewCleanupService reviewCleanupService
    ) {
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.activityService = activityService;
        this.reviewCleanupService = reviewCleanupService;
    }

    public record UpdateProjectRequest(
            @NotBlank String name,
            String description,
            String visibility,
            String status
    ) {
    }

    @PatchMapping("/{projectId}/settings")
    @Transactional
    public Object updateProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);

        requireOwner(projectId, userId);

        String visibility = normalizeVisibility(request.visibility());
        String status = normalizeStatus(request.status());

        entityManager.createNativeQuery("""
                update projects
                   set name = :name,
                       description = :description,
                       visibility = :visibility,
                       status = :status
                 where id = :id
                """)
                .setParameter("name", request.name().trim())
                .setParameter(
                        "description",
                        request.description() == null
                                ? ""
                                : request.description().trim()
                )
                .setParameter("visibility", visibility)
                .setParameter("status", status)
                .setParameter("id", projectId)
                .executeUpdate();

        activityService.record(projectId, userRepository.findById(userId).orElseThrow(), "UPDATED_PROJECT_SETTINGS", "PROJECT", projectId.toString(), "Updated project name, description, visibility or status");

        return java.util.Map.of(
                "id", projectId,
                "name", request.name().trim(),
                "description",
                request.description() == null
                        ? ""
                        : request.description().trim(),
                "visibility", visibility,
                "status", status
        );
    }

    @DeleteMapping("/{projectId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteProject(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);

        requireOwner(projectId, userId);

        // A project owns data across several tables. Because this endpoint uses
        // native SQL to remove the project row, database FK cascades are not
        // assumed. Delete children in dependency order first.
        //
        // Reviews are cleaned through the dedicated cleanup service so review
        // comments/files/notifications and review snapshot directories are
        // removed as well.
        reviewCleanupService.clearProjectReviews(projectId);

        // MainVersionFile -> MainVersion -> Project.
        entityManager.createNativeQuery("""
                delete from main_version_files
                 where version_id in (
                     select id from main_versions where project_id = :id
                 )
                """)
                .setParameter("id", projectId)
                .executeUpdate();
        entityManager.createNativeQuery(
                "delete from main_versions where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();

        // Remove COBUG's local GitHub integration records only.
        // IMPORTANT: this NEVER deletes, archives, or modifies the actual
        // repository on GitHub. The remote GitHub repository belongs to the
        // user's GitHub account/organization and must remain untouched when
        // a COBUG project is deleted.
        //
        // workspace_github_files and project_github_repositories are COBUG
        // database records. Deleting them only detaches the repository from
        // this COBUG project and resolves the FK before the project row is
        // deleted.
        entityManager.createNativeQuery(
                "delete from workspace_github_files where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_github_repositories where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();

        // Local source-workspace metadata and the remaining project-scoped
        // records.
        entityManager.createNativeQuery(
                "delete from project_source_workspaces where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_tasks where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_activity where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_chat_messages where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_resources where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_files where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_invitations where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();
        entityManager.createNativeQuery(
                "delete from project_members where project_id = :id"
        ).setParameter("id", projectId).executeUpdate();

        int deleted = entityManager.createNativeQuery(
                "delete from projects where id = :id"
        ).setParameter("id", projectId).executeUpdate();

        if (deleted == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Project not found."
            );
        }

        try {
            storageService.deleteProjectStorage(projectId);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Project was deleted, but its local storage could not be removed.",
                    e
            );
        }
    }

    private UUID currentUserId(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ))
                .getId();
    }

    private void requireOwner(UUID projectId, UUID userId) {
        try {
            Object value = entityManager
                    .createNativeQuery(
                            "select owner_id from projects where id = :id"
                    )
                    .setParameter("id", projectId)
                    .getSingleResult();

            UUID ownerId = UUID.fromString(String.valueOf(value));

            if (!ownerId.equals(userId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only the project owner can modify this project."
                );
            }
        } catch (NoResultException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Project not found."
            );
        }
    }

    private String normalizeVisibility(String value) {
        String normalized =
                String.valueOf(value == null ? "PRIVATE" : value)
                        .trim()
                        .toUpperCase();

        if (!normalized.equals("PRIVATE")
                && !normalized.equals("PUBLIC")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Visibility must be PRIVATE or PUBLIC."
            );
        }

        return normalized;
    }

    private String normalizeStatus(String value) {
        String normalized =
                String.valueOf(value == null ? "ACTIVE" : value)
                        .trim()
                        .toUpperCase();

        if (!normalized.equals("ACTIVE")
                && !normalized.equals("PLANNING")
                && !normalized.equals("ARCHIVED")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Status must be ACTIVE, PLANNING or ARCHIVED."
            );
        }

        return normalized;
    }
}
