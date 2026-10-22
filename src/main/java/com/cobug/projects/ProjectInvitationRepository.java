package com.cobug.projects;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectInvitationRepository
        extends JpaRepository<ProjectInvitation, UUID> {

    List<ProjectInvitation> findByProjectIdAndStatusOrderByCreatedAtDesc(
            UUID projectId,
            InvitationStatus status
    );

    Optional<ProjectInvitation> findByTokenAndStatus(
            String token,
            InvitationStatus status
    );

    List<ProjectInvitation> findByInvitedUserIdAndStatusOrderByCreatedAtDesc(
            UUID invitedUserId,
            InvitationStatus status
    );

    List<ProjectInvitation> findByProjectIdInOrderByCreatedAtDesc(
            List<UUID> projectIds
    );

    boolean existsByProjectIdAndEmailIgnoreCaseAndStatus(
            UUID projectId,
            String email,
            InvitationStatus status
    );
}
