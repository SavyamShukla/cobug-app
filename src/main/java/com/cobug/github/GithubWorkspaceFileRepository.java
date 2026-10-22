package com.cobug.github;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface GithubWorkspaceFileRepository extends JpaRepository<GithubWorkspaceFile, UUID> {
 List<GithubWorkspaceFile> findAllByProjectIdAndUserIdOrderByPathAsc(UUID projectId, UUID userId);
 Optional<GithubWorkspaceFile> findByProjectIdAndUserIdAndPath(UUID projectId, UUID userId, String path);
 void deleteByProjectIdAndUserId(UUID projectId, UUID userId); void deleteByProjectId(UUID projectId);
}
