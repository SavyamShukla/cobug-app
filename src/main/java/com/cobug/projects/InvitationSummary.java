package com.cobug.projects;

import java.time.Instant;
import java.util.UUID;

public record InvitationSummary(
        UUID id,
        String email,
        String role,
        String status,
        Instant createdAt
) {
}
