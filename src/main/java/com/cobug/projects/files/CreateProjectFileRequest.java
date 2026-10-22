package com.cobug.projects.files;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProjectFileRequest(

        @NotBlank
        String path,

        @NotNull
        ProjectFileType type,

        String content

) {
}
