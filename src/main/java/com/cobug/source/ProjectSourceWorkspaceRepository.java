package com.cobug.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectSourceWorkspaceRepository extends JpaRepository<ProjectSourceWorkspace, UUID> {
    Optional<ProjectSourceWorkspace> findByProjectIdAndUserId(UUID projectId, UUID userId);
}
