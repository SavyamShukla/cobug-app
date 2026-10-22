package com.cobug.security;

import com.cobug.auth.AuthResponse;
import com.cobug.auth.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native desktop Google OAuth flow for COBUG.
 *
 * The previous implementation used Google Identity Services' browser button,
 * which requires a web-style client/origin. COBUG is distributed as an
 * Electron desktop app, so we use Google's recommended loopback OAuth flow for
 * Desktop application clients instead.
 */
@Service
public class GoogleOAuthService {
    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private final ObjectMapper mapper;
    private final AuthService authService;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final HttpClient httpClient;
    private final Map<String, PendingState> states = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GoogleOAuthService(
            ObjectMapper mapper,
            AuthService authService,
            @Value("${cobug.google.client-id:}") String clientId,
            @Value("${cobug.google.client-secret:}") String clientSecret,
            @Value("${cobug.google.redirect-uri:}") String configuredRedirectUri,
            @Value("${cobug.public.api.url:https://cobug-app.onrender.com}") String publicApiUrl) {
        this.mapper = mapper;
        this.authService = authService;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        if (configuredRedirectUri != null && !configuredRedirectUri.isBlank()) {
            this.redirectUri = configuredRedirectUri.trim();
        } else {
            String base = (publicApiUrl == null || publicApiUrl.isBlank()) ? "https://cobug-app.onrender.com" : publicApiUrl.trim();
            this.redirectUri = base.replaceAll("/+$", "") + "/api/auth/google/callback";
        }
        log.info("[AUTH][GOOGLE] Configured Google redirect URI: {}", this.redirectUri);
    }

    public String start(String returnOrigin) {
        ensureConfigured();
        cleanupExpired();
        log.info("[AUTH][GOOGLE] OAuth start requested returnOrigin={}", returnOrigin);

        String state = randomState();
        String verifier = randomVerifier();
        String challenge = codeChallenge(verifier);
        String origin = normalizeOrigin(returnOrigin);
        states.put(state, new PendingState(origin, verifier, Instant.now().plus(STATE_LIFETIME)));
        log.info("[AUTH][GOOGLE] OAuth state created statePrefix={} origin={} redirectUri={} expiresInSeconds={}", state.substring(0, Math.min(8, state.length())), origin, redirectUri, STATE_LIFETIME.toSeconds());

        return GOOGLE_AUTH
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&prompt=select_account";
    }

    public String callback(String code, String state, String error) {
        cleanupExpired();
        log.info("[AUTH][GOOGLE] callback received statePresent={} codePresent={} error={}", state != null && !state.isBlank(), code != null && !code.isBlank(), error);
        PendingState pending = state == null ? null : states.remove(state);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            log.warn("[AUTH][GOOGLE] callback rejected: missing/expired state");
            return errorPage("Google sign-in session expired. Please try again.");
        }
        if (error != null && !error.isBlank()) {
            log.warn("[AUTH][GOOGLE] Google returned error={}", error);
            return errorPage("Google sign-in was cancelled or denied.");
        }
        if (code == null || code.isBlank()) {
            return errorPage("Google did not return an authorization code.");
        }

        try {
            StringBuilder form = new StringBuilder()
                    .append("code=").append(enc(code))
                    .append("&client_id=").append(enc(clientId))
                    .append("&code_verifier=").append(enc(pending.verifier()))
                    .append("&redirect_uri=").append(enc(redirectUri))
                    .append("&grant_type=authorization_code");
            // Desktop OAuth clients cannot keep secrets confidential. Google allows
            // the token exchange without client_secret; PKCE protects the code.
            if (!clientSecret.isBlank()) {
                form.append("&client_secret=").append(enc(clientSecret));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(GOOGLE_TOKEN))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();

            log.info("[AUTH][GOOGLE] exchanging authorization code with Google token endpoint");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body() == null ? "" : response.body();
                if (detail.length() > 800) detail = detail.substring(0, 800);
                log.error("[AUTH][GOOGLE] token exchange FAILED status={} body={}", response.statusCode(), detail);
                throw new IllegalStateException("Google token exchange failed (HTTP " + response.statusCode() + "). " + detail);
            }

            JsonNode json = mapper.readTree(response.body());
            log.info("[AUTH][GOOGLE] Google token exchange succeeded HTTP={}", response.statusCode());
            String idToken = json.path("id_token").asText("");
            if (idToken.isBlank()) throw new IllegalStateException("Google did not return an ID token.");

            log.info("[AUTH][GOOGLE] ID token received; verifying identity and creating COBUG session");
            AuthResponse auth = authService.googleLogin(idToken);
            log.info("[AUTH][GOOGLE] COBUG session created successfully; sending token to renderer origin={}", pending.origin());
            return successPage(pending.origin(), auth.token());
        } catch (InterruptedException e) {
            log.error("[AUTH][GOOGLE] callback interrupted", e);
            Thread.currentThread().interrupt();
            return errorPage("Google sign-in was interrupted. Please try again.");
        } catch (Exception e) {
            log.error("[AUTH][GOOGLE] callback FAILED error={}", e.getMessage(), e);
            return errorPage(e.getMessage() == null ? "Google sign-in failed. Please try again." : e.getMessage());
        }
    }

    private void ensureConfigured() {
        if (clientId.isBlank()) throw new IllegalStateException("Google login is not configured. Set GOOGLE_CLIENT_ID.");
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        states.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private String randomState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomVerifier() {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize secure Google sign-in.", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) return "http://localhost:43127";
        String origin = value.trim();
        if (origin.equals("cobug://oauth")
                || origin.equals("http://localhost:43127") || origin.equals("http://127.0.0.1:43127")
                || origin.equals("http://localhost:5173") || origin.equals("http://127.0.0.1:5173")) {
            return origin;
        }
        return "http://localhost:43127";
    }

    private static String successPage(String origin, String token) throws Exception {
        String tokenJson = new ObjectMapper().writeValueAsString(token);
        String originJson = new ObjectMapper().writeValueAsString(origin);

        // Electron desktop uses a registered cobug:// protocol. The system
        // browser can hand the OAuth result directly back to the running COBUG
        // process, so there is no intermediate bridge page and no manual
        // "Open Google" step. The JWT is placed in the URL fragment so it is
        // not sent as an HTTP request to the custom-protocol handler.
        if ("cobug://oauth".equals(origin)) {
            String callbackUrl = "cobug://oauth/callback#token="
                    + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
            String callbackJson = new ObjectMapper().writeValueAsString(callbackUrl);
            return "<!doctype html><html><head><meta charset='utf-8'><title>COBUG</title>"
                    + "<style>body{font-family:system-ui,-apple-system,sans-serif;background:#080a10;color:#fff;display:grid;place-items:center;height:100vh;margin:0}"
                    + ".box{padding:32px 36px;border:1px solid #273044;border-radius:18px;background:#111521;text-align:center;max-width:380px}"
                    + "h2{margin:0 0 8px 0;font-size:20px;font-weight:600}"
                    + "p{color:#9ca8bb;font-size:14px;margin:8px 0 20px 0}"
                    + ".btn{display:inline-block;padding:10px 24px;border-radius:10px;background:#3b82f6;color:#fff;text-decoration:none;font-weight:600;font-size:14px;box-shadow:0 4px 14px rgba(59,130,246,0.35);transition:background .2s}"
                    + ".btn:hover{background:#2563eb}</style></head><body>"
                    + "<div class='box'><h2>Signed in successfully</h2>"
                    + "<p>Returning to COBUG desktop application…</p>"
                    + "<a class='btn' href='" + callbackUrl + "'>Open COBUG</a>"
                    + "</div>"
                    + "<script>const callback=" + callbackJson + ";setTimeout(()=>{window.location.href=callback;},100);</script>"
                    + "</body></html>";
        }

        return "<!doctype html><html><head><meta charset='utf-8'><title>COBUG Google Sign-In</title>"
                + "<style>body{font-family:system-ui;background:#080a10;color:#fff;display:grid;place-items:center;height:100vh;margin:0}"
                + ".box{padding:28px 32px;border:1px solid #273044;border-radius:18px;background:#111521;text-align:center}"
                + "p{color:#9ca8bb}</style></head><body><div class='box'><b>Google sign-in complete</b>"
                + "<p>You can return to COBUG. This window will close automatically.</p></div>"
                + "<script>const token=" + tokenJson + ";const origin=" + originJson + ";"
                + "if(window.opener){window.opener.postMessage({source:'cobug-google',token},origin);setTimeout(()=>window.close(),250);}"
                + "</script></body></html>";
    }

    private static String errorPage(String message) {
        String safe = message == null ? "Google sign-in failed." : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!doctype html><html><head><meta charset='utf-8'><title>COBUG Google Sign-In</title>"
                + "<style>body{font-family:system-ui;background:#080a10;color:#fff;display:grid;place-items:center;height:100vh;margin:0}.box{padding:28px 32px;border:1px solid #273044;border-radius:18px;background:#111521;text-align:center}p{color:#ffb4b4}</style>"
                + "</head><body><div class='box'><b>Google sign-in failed</b><p>" + safe + "</p><p>You can close this window and try again.</p></div></body></html>";
    }

    private record PendingState(String origin, String verifier, Instant expiresAt) {}
}
