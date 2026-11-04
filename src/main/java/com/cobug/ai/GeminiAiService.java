package com.cobug.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class GeminiAiService implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);

    private final ObjectMapper mapper;
    private final ProjectAiContextService context;
    private final GlobalAiContextService globalContext;
    private final AiSettingsRepository settingsRepo;
    private final com.cobug.auth.UserRepository users;
    private final String defaultApiKey;
    private final String model;
    private final HttpClient http;

    public GeminiAiService(
            ObjectMapper mapper,
            ProjectAiContextService context,
            GlobalAiContextService globalContext,
            AiSettingsRepository settingsRepo,
            com.cobug.auth.UserRepository users,
            @Value("${cobug.gemini.api-key:}") String apiKey,
            @Value("${cobug.gemini.model:gemini-3.8-flash}") String model) {
        this.mapper = mapper;
        this.context = context;
        this.globalContext = globalContext;
        this.settingsRepo = settingsRepo;
        this.users = users;
        this.defaultApiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gemini-3.8-flash" : model.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String name() { return "gemini"; }

    @Override
    public String model() { return model; }

    private String resolveApiKey(String email) {
        try {
            var userOpt = users.findByEmail(email);
            if (userOpt.isPresent()) {
                var s = settingsRepo.findByUser(userOpt.get());
                if (s.isPresent() && s.get().getCustomGeminiKey() != null && !s.get().getCustomGeminiKey().isBlank()) {
                    return s.get().getCustomGeminiKey().trim();
                }
            }
        } catch (Exception ignored) {}
        return defaultApiKey;
    }

    @Override
    public String answer(UUID projectId, String email, String question) {
        return answer(projectId, email, question, List.of());
    }

    @Override
    public String answer(UUID projectId, String email, String question, List<Map<String,String>> history) {
        return answer(projectId, email, question, history, model);
    }

    @Override
    public String answer(UUID projectId, String email, String question, List<Map<String,String>> history, String modelOverride) {
        String key = resolveApiKey(email);
        if (key.isBlank()) {
            return "⚠️ **Gemini API Key Missing**: Please enter your Google Gemini API key in **Settings → AI Integration** to enable dynamic responses.";
        }

        String effectiveModel = modelOverride == null || modelOverride.isBlank() ? model : modelOverride.trim();
        String q = question == null ? "" : question.trim();

        String ctx;
        try {
            ctx = context.build(projectId, email, q);
        } catch (Exception ex) {
            ctx = "Project context could not be fully loaded. Answer using software engineering knowledge.";
        }

        String prompt = "You are Tedo, COBUG's AI assistant inside a specific COBUG project.\n"
                + "When the user asks about this project, use the supplied context. Be concise, clear, and helpful.\n\n"
                + historyPrompt(history)
                + "USER QUESTION:\n" + q + "\n\nPROJECT CONTEXT:\n" + ctx;

        return callGemini(key, prompt, effectiveModel);
    }

    @Override
    public String answerGlobal(String email, String question, List<Map<String,String>> history) {
        return answerGlobal(email, question, history, model);
    }

    @Override
    public String answerGlobal(String email, String question, List<Map<String,String>> history, String modelOverride) {
        String key = resolveApiKey(email);
        if (key.isBlank()) {
            return "⚠️ **Gemini API Key Missing**: Please enter your Google Gemini API key in **Settings → AI Integration** to enable dynamic responses.";
        }

        String effectiveModel = modelOverride == null || modelOverride.isBlank() ? model : modelOverride.trim();
        String q = question == null ? "" : question.trim();

        String ctx;
        try {
            ctx = globalContext.build(email, q);
        } catch (Exception ex) {
            ctx = "Profile context is temporarily unavailable.";
        }

        String prompt = "You are Tedo, COBUG's AI assistant across the entire COBUG platform.\n"
                + "Be friendly, clear and helpful. Use the profile context below when relevant.\n\n"
                + historyPrompt(history)
                + "USER MESSAGE:\n" + q + "\n\nWHOLE PROFILE CONTEXT:\n" + ctx;

        return callGemini(key, prompt, effectiveModel);
    }

    private String historyPrompt(List<Map<String,String>> history) {
        if (history == null || history.isEmpty()) return "RECENT CONVERSATION:\n(none)\n\n";
        StringBuilder out = new StringBuilder("RECENT CONVERSATION:\n");
        history.stream().skip(Math.max(0, history.size() - 8)).forEach(h -> {
            String role = h == null ? "user" : String.valueOf(h.getOrDefault("role", "user"));
            String content = h == null ? "" : String.valueOf(h.getOrDefault("content", ""));
            out.append(role).append(": ").append(content.length() > 1000 ? content.substring(0, 1000) + "…" : content).append("\n");
        });
        return out.append("\n").toString();
    }

    private String callGemini(String key, String prompt, String effectiveModel) {
        // Normalize model name for Google AI Studio generateContent endpoint
        String targetModel = effectiveModel;
        if (targetModel.contains("1.5") || targetModel.equals("gemini-pro")) {
            targetModel = "gemini-2.0-flash";
        }

        // Try standard Google AI Studio generateContent endpoint first
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + targetModel + ":generateContent?key=" + key;
            Map<String, Object> body = Map.of(
                    "contents", List.of(
                            Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))
                    )
            );

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                JsonNode root = mapper.readTree(res.body());
                String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                if (!text.isBlank()) return text.trim();
            } else if (res.statusCode() == 404) {
                // Retry with gemini-2.0-flash if model was not found
                String fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + key;
                HttpRequest fallbackReq = HttpRequest.newBuilder(URI.create(fallbackUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> fbRes = http.send(fallbackReq, HttpResponse.BodyHandlers.ofString());
                if (fbRes.statusCode() / 100 == 2) {
                    JsonNode root = mapper.readTree(fbRes.body());
                    String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
                    if (!text.isBlank()) return text.trim();
                }
            }
        } catch (Exception e) {
            log.warn("[GEMINI] generateContent failed, attempting interactions endpoint: {}", e.getMessage());
        }

        // Fallback to interactions endpoint
        try {
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/interactions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", targetModel);
            body.put("input", prompt);
            body.put("store", false);

            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(res.body());

            if (res.statusCode() / 100 == 2) {
                String text = root.path("output_text").asText("");
                if (!text.isBlank()) return text.trim();
            }
            String msg = root.path("error").path("message").asText("Gemini request was unsuccessful.");
            return "⚠️ **Gemini Error**: " + msg;
        } catch (Exception ex) {
            log.error("[GEMINI] Execution failed: {}", ex.getMessage());
            return "⚠️ **Error connecting to Gemini**: " + ex.getMessage();
        }
    }
}
