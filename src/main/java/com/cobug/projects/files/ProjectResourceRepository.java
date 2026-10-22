package com.cobug.projects.files;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ProjectResourceRepository extends JpaRepository<ProjectResource,UUID>{
    List<ProjectResource> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
