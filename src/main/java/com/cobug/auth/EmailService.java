package com.cobug.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * COBUG transactional email service.
 *
 * Uses Brevo's HTTPS API rather than SMTP so the backend works on Render's
 * free Web Service plan, where outbound SMTP ports are restricted.
 * The Brevo API key is backend-only and must never be exposed to the desktop app.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final URI BREVO_SEND_URI = URI.create("https://api.brevo.com/v3/smtp/email");

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String fromEmail;
    private final String fromName;

    public EmailService(
            ObjectMapper mapper,
            @Value("${cobug.brevo.api-key:}") String apiKey,
            @Value("${cobug.mail.from:}") String fromEmail,
            @Value("${cobug.mail.from-name:COBUG}") String fromName) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.fromEmail = fromEmail == null ? "" : fromEmail.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "COBUG" : fromName.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostConstruct
    void logConfiguration() {
        log.info("[MAIL] Brevo configured: apiKey={}, sender={}", !apiKey.isBlank(), fromEmail.isBlank() ? "<missing>" : fromEmail);
        if (apiKey.isBlank()) log.warn("[MAIL] BREVO_API_KEY is missing; OTP emails cannot be sent.");
        if (fromEmail.isBlank()) log.warn("[MAIL] COBUG_MAIL_FROM is missing; OTP emails cannot be sent.");
    }

    public void sendRegistrationOtp(String to, String name, String otp) {
        sendOtp(to, "Your COBUG verification code", name, otp,
                "Use this code to finish creating your COBUG account.", "registration");
    }

    public void sendPasswordResetOtp(String to, String name, String otp) {
        sendOtp(to, "Your COBUG password reset code", name, otp,
                "Use this code to reset your COBUG password.", "password reset");
    }

    // Kept for compatibility with older backend callers. The active auth flow uses OTP.
    public void sendVerificationEmail(String to, String name, String link) {
        throw new IllegalStateException("Email verification now uses OTP. Use sendRegistrationOtp instead.");
    }

    // Kept for compatibility with older backend callers. The active auth flow uses OTP.
    public void sendPasswordResetEmail(String to, String name, String link) {
        throw new IllegalStateException("Password reset now uses OTP. Use sendPasswordResetOtp instead.");
    }

    private void sendOtp(String to, String subject, String name, String otp, String purpose, String purposeLabel) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Brevo API key is not configured. Set BREVO_API_KEY.");
        }
        if (fromEmail.isBlank()) {
            throw new IllegalStateException("COBUG mail sender is not configured. Set COBUG_MAIL_FROM.");
        }

        String safeName = name == null || name.isBlank() ? "there" : name.trim();
        String text = "Hi " + safeName + ",\n\n"
                + purpose + "\n\n"
                + (purposeLabel.equals("password reset")
                    ? "Your COBUG password reset code is:\n\n"
                    : "Your COBUG verification code is:\n\n")
                + otp + "\n\n"
                + "This code expires in 10 minutes. Do not share this code with anyone.\n\n"
                + "If you did not request this, you can safely ignore this email.\n\n"
                + "— COBUG";

        try {
            Map<String, Object> sender = new LinkedHashMap<>();
            sender.put("name", fromName);
            sender.put("email", fromEmail);

            Map<String, Object> recipient = new LinkedHashMap<>();
            recipient.put("email", to);
            if (name != null && !name.isBlank()) recipient.put("name", name.trim());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sender", sender);
            payload.put("to", java.util.List.of(recipient));
            payload.put("subject", subject);
            payload.put("textContent", text);

            String json = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(BREVO_SEND_URI)
                    .timeout(Duration.ofSeconds(20))
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            log.info("[MAIL][REQUEST] purpose={} recipient={} sender={} subject={}", purposeLabel, to, fromEmail, subject);
            log.info("[MAIL][REQUEST] payload prepared; otp is intentionally not logged");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[MAIL][RESPONSE] HTTP {} recipient={} purpose={}", response.statusCode(), to, purposeLabel);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[MAIL][SUCCESS] Brevo accepted {} email recipient={} response={}", purposeLabel, to, response.body());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body() == null ? "" : response.body();
                if (detail.length() > 600) detail = detail.substring(0, 600);
                log.error("[MAIL][FAILURE] Brevo rejected email recipient={} purpose={} status={} body={}", to, purposeLabel, response.statusCode(), detail);
                throw new IllegalStateException(
                        "Brevo email delivery failed (HTTP " + response.statusCode() + "). " + detail);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Brevo email delivery was interrupted.", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Unable to send email through Brevo.", e);
        }
    }
}
