package com.cobug.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Primary
public class AiProviderRouter implements AiProvider {
    private final GeminiAiService gemini;
    private final OllamaAiService ollama;
    private final OllamaCloudAiService ollamaCloud;
    private final AiSettingsService settingsService;
    private final String defaultProvider;

    public AiProviderRouter(
            GeminiAiService gemini,
            OllamaAiService ollama,
            OllamaCloudAiService ollamaCloud,
            AiSettingsService settingsService,
            @Value("${cobug.ai.provider:ollama}") String provider) {
        this.gemini = gemini;
        this.ollama = ollama;
        this.ollamaCloud = ollamaCloud;
        this.settingsService = settingsService;
        this.defaultProvider = normalize(provider);
    }

    private AiProvider active(String email, AiSettings settings) {
        String p = normalize(settings.getProvider());
        return switch (p) {
            case "gemini" -> gemini;
            case "ollama" -> ollama;
            case "ollama-cloud" -> ollamaCloud;
            case "local-engine", "local_engine" -> new LocalEngineFallbackProvider();
            default -> new LocalEngineFallbackProvider();
        };
    }

    private static class LocalEngineFallbackProvider implements AiProvider {
        private static final String ENGINE_MSG =
                "I am COBUG's Local Intent Engine. I handle roadmap, tasks, members, and deterministic project operations. " +
                "To chat with dynamic AI models, add your API keys (Gemini, ChatGPT, Ollama, Groq) in Settings → AI Integration.";

        @Override
        public String answer(UUID projectId, String userEmail, String question) {
            return ENGINE_MSG;
        }

        @Override
        public String answer(UUID projectId, String userEmail, String question, List<Map<String, String>> history) {
            return ENGINE_MSG;
        }

        @Override
        public String answer(UUID projectId, String userEmail, String question, List<Map<String, String>> history, String model) {
            return ENGINE_MSG;
        }

        @Override
        public String answerGlobal(String userEmail, String question, List<Map<String, String>> history) {
            return ENGINE_MSG;
        }

        @Override
        public String name() {
            return "local-engine";
        }

        @Override
        public String model() {
            return "local-engine";
        }
    }

    private String effectiveModel(AiSettings settings) {
        if (settings.getModel() != null && !settings.getModel().isBlank()) return settings.getModel().trim();
        return switch (normalize(settings.getProvider())) {
            case "gemini" -> gemini.model();
            case "ollama-cloud" -> ollamaCloud.model();
            case "local-engine", "local_engine" -> "local-engine";
            default -> ollama.model();
        };
    }

    private String normalize(String provider) {
        if (provider == null || provider.isBlank()) return defaultProviderFallback();
        String p = provider.trim().toLowerCase();
        if (p.equals("local") || p.equals("ollama-local")) return "ollama";
        if (p.equals("cloud-ollama")) return "ollama-cloud";
        if (p.equals("local-engine") || p.equals("local_engine") || p.equals("intent")) return "local-engine";
        return p;
    }

    private String defaultProviderFallback() { return "ollama"; }

    private AiSettings settings(String email) {
        return settingsService.prepareRequest(email);
    }

    private String run(String email, AiSettings s, java.util.function.Function<AiProvider,String> action) {
        AiProvider selected = active(email, s);
        return action.apply(selected);
    }

    @Override
    public String answer(UUID projectId, String email, String question) {
        return answer(projectId, email, question, List.of());
    }

    @Override
    public String answer(UUID projectId, String email, String question, List<Map<String,String>> history) {
        AiSettings s = settings(email);
        return run(email, s, provider -> provider.answer(projectId, email, question, history, effectiveModel(s)));
    }

    @Override
    public String answerGlobal(String email, String question, List<Map<String,String>> history) {
        AiSettings s = settings(email);
        return run(email, s, provider -> provider.answerGlobal(email, question, history, effectiveModel(s)));
    }

    @Override
    public String name() {
        return defaultProvider;
    }

    @Override
    public String model() {
        return defaultProvider.equals("gemini") ? gemini.model()
                : defaultProvider.equals("ollama-cloud") ? ollamaCloud.model()
                : ollama.model();
    }
}
