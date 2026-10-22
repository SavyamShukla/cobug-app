package com.cobug.main;

import com.cobug.storage.StoredFileInfo;

public record MainFileResponse(
        String path,
        String name,
        String type,
        long size,
        String language
) {
    public static MainFileResponse from(StoredFileInfo file) {
        return new MainFileResponse(
                file.path(),
                file.name(),
                file.type(),
                file.size(),
                file.language()
        );
    }
}
