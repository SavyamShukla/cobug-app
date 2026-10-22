package com.cobug.projects;

import java.time.Instant;
import java.util.UUID;

public record GlobalInvitationSummary(
        UUID id,
        UUID projectId,
        String projectName,
        String role,
        String status,
        String inviterId,
        String inviterName,
        String inviterEmail,
        String recipientId,
        String recipientName,
        String recipientEmail,
        Instant createdAt
) {}
