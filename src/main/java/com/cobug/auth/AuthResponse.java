package com.cobug.auth;

public record AuthResponse(
        String token,
        String tokenType,
        String userId,
        String name,
        String email,
        String role
) {
}
