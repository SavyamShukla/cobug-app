package com.cobug.projects;

import com.cobug.auth.UserRepository;
import tools.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/aim")
public class ProjectAimController {

    private final UserRepository userRepository;
    private final com.cobug.activity.ProjectActivityService activityService;

    @PersistenceContext
    private EntityManager entityManager;

    public ProjectAimController(UserRepository userRepository, com.cobug.activity.ProjectActivityService activityService) {
        this.userRepository = userRepository;
        this.activityService = activityService;
    }

    public record SaveAimRequest(JsonNode aimDetails) {}

    @GetMapping
    public Map<String, Object> getAim(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);
        requireAccess(projectId, userId);

        String json = readAim(projectId);
        return Map.of("aimDetails", json == null || json.isBlank() ? "{}" : json);
    }

    @PutMapping
    @Transactional
    public Map<String, Object> saveAim(
            @PathVariable UUID projectId,
            @RequestBody SaveAimRequest request,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);
        requireOwner(projectId, userId);

        String json = request == null || request.aimDetails() == null
                ? "{}"
                : request.aimDetails().toString();

        entityManager.createNativeQuery("""
                update projects
                   set aim_details = :aimDetails
                 where id = :id
                """)
                .setParameter("aimDetails", json)
                .setParameter("id", projectId)
                .executeUpdate();

        var actor = userRepository.findById(userId).orElseThrow();
        activityService.record(projectId, actor, "UPDATED_PROJECT_AIM", "PROJECT_AIM", projectId.toString(), "Updated the Project Aim document");
        return Map.of("aimDetails", json, "saved", true);
    }

    private String readAim(UUID projectId) {
        try {
            Object value = entityManager.createNativeQuery(
                    "select aim_details from projects where id = :id"
            ).setParameter("id", projectId).getSingleResult();
            return value == null ? "{}" : String.valueOf(value);
        } catch (NoResultException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found.");
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
            Object value = entityManager.createNativeQuery(
                    "select owner_id from projects where id = :id"
            ).setParameter("id", projectId).getSingleResult();

            if (!UUID.fromString(String.valueOf(value)).equals(userId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only the project owner can edit Project Aim."
                );
            }
        } catch (NoResultException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found.");
        }
    }

    private void requireAccess(UUID projectId, UUID userId) {
        try {
            Object owner = entityManager.createNativeQuery(
                    "select owner_id from projects where id = :id"
            ).setParameter("id", projectId).getSingleResult();

            if (UUID.fromString(String.valueOf(owner)).equals(userId)) return;

            Number count = (Number) entityManager.createNativeQuery("""
                    select count(*)
                      from project_members
                     where project_id = :projectId
                       and user_id = :userId
                    """)
                    .setParameter("projectId", projectId)
                    .setParameter("userId", userId)
                    .getSingleResult();

            if (count.longValue() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "You do not have access to this project."
                );
            }
        } catch (NoResultException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found.");
        }
    }
}
