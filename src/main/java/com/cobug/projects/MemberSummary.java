package com.cobug.projects;

import java.time.Instant;
import java.util.UUID;

public record MemberSummary(
        UUID userId,
        String name,
        String email,
        String role,
        Instant joinedAt
) {
}
