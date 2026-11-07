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
    private final OpenAiAiService openAi;
    private final GroqAiService groq;
    private final OllamaAiService ollama;
    private final OllamaCloudAiService ollamaCloud;
    private final AiSettingsService settingsService;
    private final String defaultProvider;

    public AiProviderRouter(
            GeminiAiService gemini,
            OpenAiAiService openAi,
            GroqAiService groq,
            OllamaAiService ollama,
            OllamaCloudAiService ollamaCloud,
            AiSettingsService settingsService,
            @Value("${cobug.ai.provider:ollama}") String provider) {
        this.gemini = gemini;
        this.openAi = openAi;
        this.groq = groq;
        this.ollama = ollama;
        this.ollamaCloud = ollamaCloud;
        this.settingsService = settingsService;
        this.defaultProvider = normalize(provider);
    }

    private AiProvider active(String email, AiSettings settings) {
        String plan = settings == null || settings.getPlan() == null || settings.getPlan().isBlank() ? "go" : settings.getPlan().toLowerCase().trim();
        if ("go".equals(plan)) {
            return new LocalEngineFallbackProvider();
        }

        String p = normalize(settings.getProvider());

        // For paid subscribers (Plus & Pro): if provider is local-engine or empty, auto-route to configured provider
        if ("local-engine".equals(p) || "local_engine".equals(p) || p.isBlank()) {
            if (hasText(settings.getCustomGeminiKey())) return gemini;
            if (hasText(settings.getCustomGroqKey())) return groq;
            if (hasText(settings.getCustomOpenAiKey())) return openAi;
            return gemini;
        }

        return switch (p) {
            case "gemini" -> gemini;
            case "openai", "chatgpt" -> openAi;
            case "groq" -> groq;
            case "ollama" -> ollama;
            case "ollama-cloud", "ollama_cloud", "cloud-ollama", "cloud_ollama" -> ollamaCloud;
            default -> gemini;
        };
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static class LocalEngineFallbackProvider implements AiProvider {
        private static final String ENGINE_MSG =
                "I am COBUG's Local Intent Engine. I handle roadmap, tasks, members, and deterministic project operations. " +
                "To chat with dynamic AI models (Gemini, ChatGPT, Groq, Ollama), upgrade to COBUG Plus (₹1/month) or Pro (₹2/month) in Settings → AI Integration.";

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
            case "openai", "chatgpt" -> openAi.model();
            case "groq" -> groq.model();
            case "ollama-cloud", "ollama_cloud", "cloud-ollama", "cloud_ollama" -> ollamaCloud.model();
            case "local-engine", "local_engine" -> "local-engine";
            default -> ollama.model();
        };
    }

    private String normalize(String provider) {
        if (provider == null || provider.isBlank()) return defaultProviderFallback();
        String p = provider.trim().toLowerCase();
        if (p.equals("local") || p.equals("ollama-local")) return "ollama";
        if (p.equals("cloud-ollama") || p.equals("cloud_ollama") || p.equals("ollama-cloud") || p.equals("ollama_cloud")) return "ollama_cloud";
        if (p.equals("chatgpt")) return "openai";
        if (p.equals("groq") || p.equals("groq-cloud")) return "groq";
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
                : defaultProvider.equals("openai") ? openAi.model()
                : defaultProvider.equals("groq") ? groq.model()
                : defaultProvider.equals("ollama-cloud") ? ollamaCloud.model()
                : ollama.model();
    }
}
