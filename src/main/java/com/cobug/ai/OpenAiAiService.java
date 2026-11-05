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
public class OpenAiAiService implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiAiService.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final ObjectMapper mapper;
    private final ProjectAiContextService context;
    private final GlobalAiContextService globalContext;
    private final AiSettingsRepository settingsRepo;
    private final com.cobug.auth.UserRepository users;
    private final String defaultApiKey;
    private final String defaultModel;
    private final HttpClient http;

    public OpenAiAiService(
            ObjectMapper mapper,
            ProjectAiContextService context,
            GlobalAiContextService globalContext,
            AiSettingsRepository settingsRepo,
            com.cobug.auth.UserRepository users,
            @Value("${cobug.openai.api-key:}") String defaultApiKey,
            @Value("${cobug.openai.model:gpt-4o-mini}") String defaultModel) {
        this.mapper = mapper;
        this.context = context;
        this.globalContext = globalContext;
        this.settingsRepo = settingsRepo;
        this.users = users;
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "gpt-4o-mini" : defaultModel.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String name() { return "openai"; }

    @Override
    public String model() { return defaultModel; }

    private String resolveApiKey(String email) {
        try {
            var userOpt = users.findByEmail(email);
            if (userOpt.isPresent()) {
                var s = settingsRepo.findByUser(userOpt.get());
                if (s.isPresent() && s.get().getCustomOpenAiKey() != null && !s.get().getCustomOpenAiKey().isBlank()) {
                    return s.get().getCustomOpenAiKey().trim();
                }
            }
        } catch (Exception ignored) {}
        return defaultApiKey;
    }

    @Override
    public String answer(UUID projectId, String email, String question) {
        return answer(projectId, email, question, List.of(), defaultModel);
    }

    @Override
    public String answer(UUID projectId, String email, String question, List<Map<String, String>> history) {
        return answer(projectId, email, question, history, defaultModel);
    }

    @Override
    public String answer(UUID projectId, String email, String question, List<Map<String, String>> history, String modelOverride) {
        String key = resolveApiKey(email);
        if (key.isBlank()) {
            return "⚠️ **OpenAI API Key Missing**: Please enter your OpenAI / ChatGPT API key in **Settings → AI Integration** to enable dynamic responses.";
        }

        String effectiveModel = modelOverride == null || modelOverride.isBlank() ? defaultModel : modelOverride.trim();
        String ctx;
        try {
            ctx = context.build(projectId, email, question);
        } catch (Exception ex) {
            ctx = "Project context could not be fully loaded. Answer the question using general knowledge.";
        }

        String systemPrompt = "You are Tedo, COBUG's AI assistant and expert software engineering companion. Analyze code, explain architecture, diagnose bugs, and help with project operations.\n"
                + "USER CONTEXT & RELEVANT PROJECT DETAILS:\n" + ctx;

        return executeChat(key, effectiveModel, systemPrompt, question, history);
    }

    @Override
    public String answerGlobal(String email, String question, List<Map<String, String>> history) {
        return answerGlobal(email, question, history, defaultModel);
    }

    @Override
    public String answerGlobal(String email, String question, List<Map<String, String>> history, String modelOverride) {
        String key = resolveApiKey(email);
        if (key.isBlank()) {
            return "⚠️ **OpenAI API Key Missing**: Please enter your OpenAI / ChatGPT API key in **Settings → AI Integration** to enable dynamic responses.";
        }

        String effectiveModel = modelOverride == null || modelOverride.isBlank() ? defaultModel : modelOverride.trim();
        String ctx;
        try {
            ctx = globalContext.build(email, question);
        } catch (Exception ex) {
            ctx = "User profile context is unavailable.";
        }

        String systemPrompt = "You are Tedo, COBUG's AI assistant across the entire platform.\n"
                + "USER PROFILE CONTEXT:\n" + ctx;

        return executeChat(key, effectiveModel, systemPrompt, question, history);
    }

    private String executeChat(String key, String model, String systemPrompt, String question, List<Map<String, String>> history) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            if (history != null && !history.isEmpty()) {
                history.stream().skip(Math.max(0, history.size() - 8)).forEach(h -> {
                    String role = "user".equalsIgnoreCase(h.get("role")) ? "user" : "assistant";
                    String content = h.getOrDefault("content", "");
                    if (!content.isBlank()) {
                        messages.add(Map.of("role", role, "content", content));
                    }
                });
            }

            messages.add(Map.of("role", "user", "content", question));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(res.body());

            if (res.statusCode() / 100 != 2) {
                String errorMsg = root.path("error").path("message").asText("OpenAI API request failed.");
                log.error("[OPENAI] Error status={}: {}", res.statusCode(), errorMsg);
                return "⚠️ **OpenAI Error**: " + errorMsg;
            }

            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return content.isBlank() ? "No response received from OpenAI." : content.trim();
        } catch (Exception e) {
            log.error("[OPENAI] Exception: {}", e.getMessage(), e);
            return "⚠️ **Error connecting to OpenAI**: " + e.getMessage();
        }
    }
}

