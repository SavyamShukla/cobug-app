package com.cobug.projects;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository
        extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findByProjectIdOrderByJoinedAtAsc(UUID projectId);

    List<ProjectMember> findByUserIdOrderByJoinedAtAsc(UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(
            UUID projectId,
            UUID userId
    );

    boolean existsByProjectIdAndUserId(
            UUID projectId,
            UUID userId
    );

    void deleteByProjectIdAndUserId(
            UUID projectId,
            UUID userId
    );
}
