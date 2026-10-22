package com.cobug.workspace;

import com.cobug.storage.StoredFileInfo;

public record WorkspaceFileResponse(
        String path,
        String name,
        String type,
        long size,
        String language
) {
    public static WorkspaceFileResponse from(StoredFileInfo file) {
        return new WorkspaceFileResponse(
                file.path(),
                file.name(),
                file.type(),
                file.size(),
                file.language()
        );
    }
}
