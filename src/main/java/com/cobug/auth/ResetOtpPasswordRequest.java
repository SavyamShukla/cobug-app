package com.cobug.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetOtpPasswordRequest(
        @NotBlank(message = "OTP challenge is required") String challengeId,
        @NotBlank(message = "Password is required") @Size(min = 8, max = 100, message = "Password must contain 8 to 100 characters") String password
) {}
