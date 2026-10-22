package com.cobug.auth;

import com.cobug.security.GoogleTokenVerifierService;
import com.cobug.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration OTP_LIFETIME = Duration.ofMinutes(10);
    private static final Duration OTP_RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_OTP_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final AuthOtpChallengeRepository otpRepository;
    private final EmailService emailService;
    private final GoogleTokenVerifierService googleTokenVerifierService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       EmailVerificationTokenRepository emailVerificationTokenRepository,
                       AuthOtpChallengeRepository otpRepository,
                       EmailService emailService,
                       GoogleTokenVerifierService googleTokenVerifierService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.otpRepository = otpRepository;
        this.emailService = emailService;
        this.googleTokenVerifierService = googleTokenVerifierService;
    }

    /**
     * Registration is intentionally two-phase. No User row is created here.
     * The account is created only after the email OTP is verified.
     */
    @Transactional
    public String sendRegistrationOtp(RegistrationOtpRequest request) {
        String email = normalize(request.email());
        String name = request.name().trim();
        log.info("[AUTH][REGISTER] send OTP requested email={} name={}", email, name);
        if (name.length() < 2) throw new IllegalArgumentException("Name must contain at least 2 characters");
        if (request.password().length() < 8) throw new IllegalArgumentException("Password must contain at least 8 characters");

        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null && existing.isEmailVerified()) {
            throw new IllegalArgumentException("Email already registered. Please log in or continue with Google.");
        }

        AuthOtpChallenge previous = otpRepository.findTopByEmailAndPurposeOrderByLastSentAtDesc(email, OtpPurpose.REGISTRATION)
                .orElse(null);
        if (previous != null && previous.getLastSentAt() != null
                && previous.getLastSentAt().plus(OTP_RESEND_COOLDOWN).isAfter(Instant.now())) {
            long seconds = Math.max(1, Duration.between(Instant.now(), previous.getLastSentAt().plus(OTP_RESEND_COOLDOWN)).getSeconds());
            throw new IllegalArgumentException("Please wait " + seconds + " seconds before requesting another OTP.");
        }

        otpRepository.deleteByEmailAndPurpose(email, OtpPurpose.REGISTRATION);

        String otp = generateOtp();
        AuthOtpChallenge challenge = new AuthOtpChallenge();
        challenge.setEmail(email);
        challenge.setPurpose(OtpPurpose.REGISTRATION);
        challenge.setOtpHash(sha256(otp));
        challenge.setExpiresAt(Instant.now().plus(OTP_LIFETIME));
        challenge.setLastSentAt(Instant.now());
        challenge.setAttempts(0);
        challenge.setVerified(false);
        challenge.setRegistrationName(name);
        challenge.setRegistrationPasswordHash(passwordEncoder.encode(request.password()));
        if (existing != null) challenge.setUserId(existing.getId());
        challenge = otpRepository.save(challenge);
        log.info("[AUTH][REGISTER] OTP challenge created id={} email={} expiresAt={}", challenge.getId(), email, challenge.getExpiresAt());

        try {
            emailService.sendRegistrationOtp(email, name, otp);
            log.info("[AUTH][REGISTER] OTP email send completed challengeId={} email={}", challenge.getId(), email);
        } catch (Exception e) {
            log.error("[AUTH][REGISTER] OTP email send FAILED challengeId={} email={} error={}", challenge.getId(), email, e.getMessage(), e);
            throw e;
        }
        return challenge.getId().toString();
    }

    @Transactional
    public AuthResponse verifyRegistrationOtp(VerifyOtpRequest request) {
        log.info("[AUTH][REGISTER] OTP verification requested challengeId={}", request.uuid());
        AuthOtpChallenge challenge = otpRepository.findByIdAndPurpose(request.uuid(), OtpPurpose.REGISTRATION)
                .orElseThrow(() -> new IllegalArgumentException("This OTP is invalid or has expired."));
        validateOtp(challenge, request.otp());
        log.info("[AUTH][REGISTER] OTP verified challengeId={} email={}", challenge.getId(), challenge.getEmail());

        User user;
        if (challenge.getUserId() != null) {
            user = userRepository.findById(challenge.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Account no longer exists."));
            user.setName(challenge.getRegistrationName());
            user.setPassword(challenge.getRegistrationPasswordHash());
        } else {
            if (userRepository.existsByEmail(challenge.getEmail())) {
                throw new IllegalArgumentException("Email already registered. Please log in.");
            }
            user = new User();
            user.setName(challenge.getRegistrationName());
            user.setEmail(challenge.getEmail());
            user.setPassword(challenge.getRegistrationPasswordHash());
            user.setRole(UserRole.MEMBER);
        }
        user.setEmailVerified(true);
        user.setAuthProvider("EMAIL");
        user = userRepository.save(user);
        log.info("[AUTH][REGISTER] account created/activated userId={} email={} provider={}", user.getId(), user.getEmail(), user.getAuthProvider());
        challenge.setVerified(true);
        otpRepository.save(challenge);
        return authResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());
        log.info("[AUTH][LOGIN] login requested email={}", email);
        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null && !existing.isEmailVerified()) {
            throw new IllegalArgumentException("Please verify your email with the OTP before logging in.");
        }
        if (existing != null && "GOOGLE".equalsIgnoreCase(existing.getAuthProvider())
                && (existing.getPassword() == null || existing.getPassword().isBlank())) {
            throw new IllegalArgumentException("This account uses Google sign-in. Please continue with Google.");
        }
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        log.info("[AUTH][LOGIN] login successful userId={} email={}", user.getId(), user.getEmail());
        return authResponse(user);
    }

    /** Legacy endpoint retained only so old clients fail safely. New UI uses OTP. */
    public void register(RegisterRequest request) {
        sendRegistrationOtp(new RegistrationOtpRequest(request.name(), request.email(), request.password()));
    }

    /** Legacy link verification is no longer used by the UI. */
    public void verifyEmail(String rawToken) {
        throw new IllegalArgumentException("Email verification now uses the OTP shown in COBUG.");
    }

    public void resendVerification(String email) {
        throw new IllegalArgumentException("Email verification now uses the OTP shown in COBUG.");
    }

    @Transactional
    public String sendPasswordResetOtp(String email) {
        String normalized = normalize(email);
        log.info("[AUTH][RESET] password-reset OTP requested email={}", normalized);
        Optional<User> optionalUser = userRepository.findByEmail(normalized);

        AuthOtpChallenge previous = otpRepository.findTopByEmailAndPurposeOrderByLastSentAtDesc(normalized, OtpPurpose.PASSWORD_RESET).orElse(null);
        if (previous != null && previous.getLastSentAt() != null
                && previous.getLastSentAt().plus(OTP_RESEND_COOLDOWN).isAfter(Instant.now())) {
            // Keep the response generic so the endpoint does not reveal whether an account exists.
            throw new IllegalArgumentException("Please wait before requesting another OTP.");
        }

        // Always return a challenge-looking response to avoid leaking whether an email exists.
        otpRepository.deleteByEmailAndPurpose(normalized, OtpPurpose.PASSWORD_RESET);
        String otp = generateOtp();
        AuthOtpChallenge challenge = new AuthOtpChallenge();
        challenge.setEmail(normalized);
        challenge.setPurpose(OtpPurpose.PASSWORD_RESET);
        challenge.setOtpHash(sha256(otp));
        challenge.setExpiresAt(Instant.now().plus(OTP_LIFETIME));
        challenge.setLastSentAt(Instant.now());
        challenge.setAttempts(0);
        challenge.setVerified(false);
        if (optionalUser.isPresent()) challenge.setUserId(optionalUser.get().getId());
        challenge = otpRepository.save(challenge);
        log.info("[AUTH][RESET] OTP challenge created id={} email={} accountFound={} expiresAt={}", challenge.getId(), normalized, optionalUser.isPresent(), challenge.getExpiresAt());

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            try {
                emailService.sendPasswordResetOtp(user.getEmail(), user.getName(), otp);
                log.info("[AUTH][RESET] OTP email send completed challengeId={} email={}", challenge.getId(), normalized);
            } catch (Exception e) {
                log.error("[AUTH][RESET] OTP email send FAILED challengeId={} email={} error={}", challenge.getId(), normalized, e.getMessage(), e);
                throw e;
            }
        } else {
            log.info("[AUTH][RESET] no account found for email={}; no email sent", normalized);
        }
        return challenge.getId().toString();
    }

    @Transactional
    public void verifyPasswordResetOtp(VerifyOtpRequest request) {
        log.info("[AUTH][RESET] OTP verification requested challengeId={}", request.uuid());
        AuthOtpChallenge challenge = otpRepository.findByIdAndPurpose(request.uuid(), OtpPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new IllegalArgumentException("This OTP is invalid or has expired."));
        validateOtp(challenge, request.otp());
        if (challenge.getUserId() == null) {
            throw new IllegalArgumentException("This OTP is invalid or has expired.");
        }
        challenge.setVerified(true);
        otpRepository.save(challenge);
    }

    @Transactional
    public void resetPasswordWithOtp(ResetOtpPasswordRequest request) {
        UUID id;
        try { id = UUID.fromString(request.challengeId()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid OTP challenge."); }
        AuthOtpChallenge challenge = otpRepository.findByIdAndPurpose(id, OtpPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new IllegalArgumentException("Password reset session is invalid or expired."));
        if (!challenge.isVerified() || challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Please verify the OTP before changing your password.");
        }
        if (challenge.getUserId() == null) throw new IllegalArgumentException("Password reset session is invalid or expired.");
        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmailVerified(true);
        user.setAuthProvider("EMAIL");
        userRepository.save(user);
        otpRepository.deleteById(challenge.getId());
    }

    /** Legacy link reset endpoint retained for compatibility with old clients. */
    public void forgotPassword(String email) {
        sendPasswordResetOtp(email);
    }

    /** Legacy token reset endpoint retained for compatibility with old clients. */
    public void resetPassword(String rawToken, String newPassword) {
        throw new IllegalArgumentException("Password reset now uses OTP verification in COBUG.");
    }

    public AuthResponse googleLogin(String credential) {
        log.info("[AUTH][GOOGLE] credential verification started credentialPresent={}", credential != null && !credential.isBlank());
        GoogleTokenVerifierService.GoogleProfile profile = googleTokenVerifierService.verify(credential);
        String email = normalize(profile.email());
        log.info("[AUTH][GOOGLE] Google identity verified email={} subject={} name={}", email, profile.subject(), profile.name());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = new User();
            user.setName(profile.name() == null || profile.name().isBlank() ? email.split("@")[0] : profile.name());
            user.setEmail(email);
            user.setPassword(null);
            user.setRole(UserRole.MEMBER);
            user.setEmailVerified(true);
            user.setAuthProvider("GOOGLE");
            user.setGoogleSubject(profile.subject());
            user = userRepository.save(user);
            log.info("[AUTH][GOOGLE] new Google account created userId={} email={}", user.getId(), email);
        } else {
            log.info("[AUTH][GOOGLE] existing account found userId={} email={} provider={}", user.getId(), email, user.getAuthProvider());
            user.setEmailVerified(true);
            user.setGoogleSubject(profile.subject());
            if (user.getPassword() == null) user.setAuthProvider("GOOGLE");
            userRepository.save(user);
        }
        log.info("[AUTH][GOOGLE] Google login successful userId={} email={} provider={}", user.getId(), user.getEmail(), user.getAuthProvider());
        return authResponse(user);
    }

    private void validateOtp(AuthOtpChallenge challenge, String rawOtp) {
        log.info("[AUTH][OTP] validating challengeId={} purpose={} attempts={} expiresAt={}", challenge.getId(), challenge.getPurpose(), challenge.getAttempts(), challenge.getExpiresAt());
        if (challenge.isVerified() || challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This OTP is invalid or has expired.");
        }
        if (challenge.getAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new IllegalArgumentException("Too many incorrect OTP attempts. Please request a new OTP.");
        }
        String normalized = rawOtp == null ? "" : rawOtp.replaceAll("\\D", "");
        if (normalized.length() != 6 || !sha256(normalized).equals(challenge.getOtpHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            otpRepository.save(challenge);
            log.warn("[AUTH][OTP] incorrect OTP challengeId={} attempt={}", challenge.getId(), challenge.getAttempts());
            throw new IllegalArgumentException("Incorrect OTP. Please check the code and try again.");
        }
    }

    private AuthResponse authResponse(User user) {
        return new AuthResponse(jwtService.generateToken(user.getEmail()), "Bearer", user.getId().toString(), user.getName(), user.getEmail(), user.getRole().name());
    }

    private static String normalize(String email) { return email.toLowerCase().trim(); }
    private static String generateOtp() { return String.format("%06d", new SecureRandom().nextInt(1_000_000)); }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Unable to secure OTP", e); }
    }
}
