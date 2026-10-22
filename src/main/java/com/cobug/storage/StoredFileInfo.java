package com.cobug.storage;

public record StoredFileInfo(
        String path,
        String name,
        String type,
        long size,
        String language
) {
}
