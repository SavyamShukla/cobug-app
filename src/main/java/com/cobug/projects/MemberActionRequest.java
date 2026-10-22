package com.cobug.projects;

import jakarta.validation.constraints.NotBlank;

public record MemberActionRequest(
        @NotBlank String role
) {
}
