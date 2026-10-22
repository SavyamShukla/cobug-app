package com.cobug.projects;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RoadmapPointRepository extends JpaRepository<RoadmapPoint, UUID> {
    List<RoadmapPoint> findByStationIdOrderByPositionAsc(UUID stationId);
    void deleteByStationId(UUID stationId);
}
