package com.cobug.workspace;

import java.util.List;

public record WorkspaceResponse(
        String projectId,
        String projectName,
        String userId,
        String userEmail,
        List<WorkspaceFileResponse> files
) {
}
