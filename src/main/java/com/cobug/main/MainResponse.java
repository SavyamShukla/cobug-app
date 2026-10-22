package com.cobug.main;

import java.util.List;

public record MainResponse(
        String projectId,
        String projectName,
        String ownerId,
        String ownerEmail,
        String rootFolder,
        List<MainFileResponse> files
) {
}
