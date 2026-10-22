package com.cobug.projects;

import java.time.LocalDateTime;

public record ProjectResponse(
        String id,
        String name,
        String description,
        String ownerId,
        String ownerName,
        String status,
        LocalDateTime createdAt,
        int progress,
        long roadmapStations,
        long completedRoadmapStations
) {}
