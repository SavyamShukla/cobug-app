package com.cobug.tasks;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ProjectTaskRepository extends JpaRepository<ProjectTask,UUID>{
    List<ProjectTask> findByProjectIdAndVisibilityOrderByCreatedAtDesc(UUID projectId, String visibility);
    List<ProjectTask> findByProjectIdAndCreatedByOrderByCreatedAtDesc(UUID projectId, UUID createdBy);
    List<ProjectTask> findByProjectIdAndAssigneeIdOrderByCreatedAtDesc(UUID projectId, UUID assigneeId);
    Optional<ProjectTask> findByIdAndProjectId(UUID id, UUID projectId);
    List<ProjectTask> findTop50ByProjectIdOrderByUpdatedAtDesc(UUID projectId);
    List<ProjectTask> findTop50ByProjectIdAndVisibilityOrderByUpdatedAtDesc(UUID projectId, String visibility);
    List<ProjectTask> findTop50ByProjectIdAndCreatedByOrderByUpdatedAtDesc(UUID projectId, UUID createdBy);
    List<ProjectTask> findTop50ByProjectIdAndAssigneeIdOrderByUpdatedAtDesc(UUID projectId, UUID assigneeId);
}
