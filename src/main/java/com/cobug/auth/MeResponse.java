package com.cobug.auth;

public record MeResponse(
        String userId,
        String name,
        String email,
        String role
) {
}
