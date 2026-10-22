package com.cobug.projects;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RoadmapStationRepository extends JpaRepository<RoadmapStation, UUID> {
    List<RoadmapStation> findByProjectIdOrderByPositionAsc(UUID projectId);
    long countByProjectId(UUID projectId);
    long countByProjectIdAndStatus(UUID projectId, String status);
}
