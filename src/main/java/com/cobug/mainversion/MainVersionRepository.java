package com.cobug.mainversion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface MainVersionRepository extends JpaRepository<MainVersion, UUID> {
    List<MainVersion> findByProjectIdOrderByVersionNumberDesc(UUID projectId);
    Optional<MainVersion> findTopByProjectIdOrderByVersionNumberDesc(UUID projectId);
    Optional<MainVersion> findByProjectIdAndSourceCommitSha(UUID projectId, String sourceCommitSha);
    Optional<MainVersion> findTopByProjectIdAndPushedCommitShaIsNotNullOrderByVersionNumberDesc(UUID projectId);
    int deleteByProjectId(UUID projectId);
}
