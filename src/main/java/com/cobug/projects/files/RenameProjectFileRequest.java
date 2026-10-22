package com.cobug.projects.files;

import jakarta.validation.constraints.NotBlank;

public record RenameProjectFileRequest(

        @NotBlank
        String newPath

) {
}
