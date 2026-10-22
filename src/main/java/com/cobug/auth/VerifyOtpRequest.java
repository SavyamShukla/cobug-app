package com.cobug.auth;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record VerifyOtpRequest(
        @NotBlank(message = "OTP challenge is required") String challengeId,
        @NotBlank(message = "OTP is required") String otp
) {
    public UUID uuid() {
        try { return UUID.fromString(challengeId); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid OTP challenge."); }
    }
}
