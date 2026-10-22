package com.cobug.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import com.cobug.security.GoogleOAuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final UserRepository userRepository;
    private final GoogleOAuthService googleOAuthService;

    public AuthController(AuthService authService, UserRepository userRepository, GoogleOAuthService googleOAuthService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.googleOAuthService = googleOAuthService;
    }

    @PostMapping("/register/send-otp")
    public OtpChallengeResponse sendRegistrationOtp(@Valid @RequestBody RegistrationOtpRequest request) {
        return new OtpChallengeResponse(authService.sendRegistrationOtp(request), 600);
    }

    @PostMapping("/register/verify-otp")
    public AuthResponse verifyRegistrationOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return authService.verifyRegistrationOtp(request);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request.credential());
    }

    @GetMapping("/google/start")
    public Map<String, String> googleStart(
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestParam(value = "returnOrigin", required = false) String returnOrigin) {
        // Desktop COBUG explicitly requests the registered cobug:// callback.
        // Normal web login continues to use the browser Origin header.
        String target = (returnOrigin == null || returnOrigin.isBlank()) ? origin : returnOrigin;
        return Map.of("authorizationUrl", googleOAuthService.start(target));
    }

    @GetMapping(value = "/google/callback", produces = "text/html")
    public String googleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error) {
        return googleOAuthService.callback(code, state, error);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.resendVerification(request.email());
    }

    @GetMapping("/verify-email")
    public String verifyEmailLegacy() {
        return "Email verification now uses the OTP shown in COBUG.";
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return new MeResponse(user.getId().toString(), user.getName(), user.getEmail(), user.getRole().name());
    }

    @PostMapping("/forgot-password/send-otp")
    public OtpChallengeResponse sendPasswordResetOtp(@Valid @RequestBody ForgotPasswordRequest request) {
        return new OtpChallengeResponse(authService.sendPasswordResetOtp(request.email()), 600);
    }

    @PostMapping("/forgot-password/verify-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyPasswordResetOtp(@Valid @RequestBody VerifyOtpRequest request) {
        authService.verifyPasswordResetOtp(request);
    }

    @PostMapping("/forgot-password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPasswordWithOtp(@Valid @RequestBody ResetOtpPasswordRequest request) {
        authService.resetPasswordWithOtp(request);
    }

    // Legacy endpoints remain available so an older client receives a controlled response.
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.password());
    }
}
