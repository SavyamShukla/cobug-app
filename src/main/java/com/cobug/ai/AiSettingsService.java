package com.cobug.ai;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiSettingsService {
    private final AiSettingsRepository repository;
    private final UserRepository users;
    private final AiCreditTransactionRepository transactions;
    private final String defaultProvider;
    private final String defaultModel;
    private final String defaultLocalUrl;
    private final String defaultCloudModel;
    private final String defaultGeminiModel;
    private final boolean testActivationEnabled;
    private final long activationCredits;

    public AiSettingsService(
            AiSettingsRepository repository,
            UserRepository users,
            AiCreditTransactionRepository transactions,
            @Value("${cobug.ai.provider:ollama}") String defaultProvider,
            @Value("${cobug.ollama.model:qwen2.5-coder:3b}") String defaultModel,
            @Value("${cobug.ollama.url:http://localhost:11434}") String defaultLocalUrl,
            @Value("${cobug.ollama.cloud.model:gpt-oss:20b}") String defaultCloudModel,
            @Value("${cobug.gemini.model:gemini-3.8-flash}") String defaultGeminiModel,
            @Value("${cobug.ai.test-activation.enabled:false}") boolean testActivationEnabled,
            @Value("${cobug.ai.activation-credits:1000}") long activationCredits) {
        this.repository = repository;
        this.users = users;
        this.transactions = transactions;
        this.defaultProvider = normalizeProvider(defaultProvider);
        this.defaultModel = defaultModel == null ? "" : defaultModel.trim();
        this.defaultLocalUrl = defaultLocalUrl == null || defaultLocalUrl.isBlank() ? "http://localhost:11434" : defaultLocalUrl.trim();
        this.defaultCloudModel = defaultCloudModel == null || defaultCloudModel.isBlank() ? "gpt-oss:20b" : defaultCloudModel.trim();
        this.defaultGeminiModel = defaultGeminiModel == null || defaultGeminiModel.isBlank() ? "gemini-3.8-flash" : defaultGeminiModel.trim();
        this.testActivationEnabled = testActivationEnabled;
        this.activationCredits = Math.max(1, activationCredits);
    }

    public AiSettings getOrCreate(String email) {
        User user = user(email);
        return repository.findByUser(user).orElseGet(() -> {
            AiSettings settings = new AiSettings();
            settings.setUser(user);
            settings.setProvider(defaultProvider);
            settings.setModel(defaultModel);
            settings.setLocalOllamaUrl(defaultLocalUrl);
            settings.setCredits(0);
            return repository.save(settings);
        });
    }

    public Map<String,Object> dto(String email) {
        AiSettings s = getOrCreate(email);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("provider", normalizeProvider(s.getProvider()));
        out.put("model", s.getModel());
        out.put("localOllamaUrl", s.getLocalOllamaUrl());
        out.put("cloudEnabled", s.isCloudEnabled());
        out.put("credits", s.getCredits());
        out.put("plan", s.getPlan());
        out.put("customGeminiKey", s.getCustomGeminiKey() == null ? "" : s.getCustomGeminiKey());
        out.put("customOpenAiKey", s.getCustomOpenAiKey() == null ? "" : s.getCustomOpenAiKey());
        out.put("customOllamaUrl", s.getCustomOllamaUrl() == null ? "http://localhost:11434" : s.getCustomOllamaUrl());
        out.put("customOllamaModel", s.getCustomOllamaModel() == null ? "qwen2.5-coder:3b" : s.getCustomOllamaModel());
        out.put("includeProjectContext", s.isIncludeProjectContext());
        out.put("includeActivityContext", s.isIncludeActivityContext());
        out.put("automaticAssistance", s.isAutomaticAssistance());
        out.put("testActivationEnabled", testActivationEnabled);
        out.put("activationCredits", activationCredits);
        out.put("cloudProviders", new String[]{"ollama-cloud", "gemini", "openai"});
        out.put("transactions", transactions.findTop20ByUserOrderByCreatedAtDesc(s.getUser()).stream().map(t -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("amount", t.getAmount());
            row.put("balanceAfter", t.getBalanceAfter());
            row.put("type", t.getType());
            row.put("description", t.getDescription());
            row.put("createdAt", t.getCreatedAt());
            return row;
        }).toList());
        return out;
    }

    public AiSettings update(String email, String provider, String model, String localUrl,
                             Boolean includeProjectContext, Boolean includeActivityContext,
                             Boolean automaticAssistance) {
        return update(email, provider, model, localUrl, includeProjectContext, includeActivityContext, automaticAssistance, null, null, null, null, null);
    }

    public AiSettings update(String email, String provider, String model, String localUrl,
                             Boolean includeProjectContext, Boolean includeActivityContext,
                             Boolean automaticAssistance, String plan,
                             String customGeminiKey, String customOpenAiKey,
                             String customOllamaUrl, String customOllamaModel) {
        AiSettings s = getOrCreate(email);
        String currentPlan = s.getPlan() == null || s.getPlan().isBlank() ? "go" : s.getPlan().toLowerCase().trim();

        if (plan != null && !plan.isBlank() && "go".equalsIgnoreCase(plan.trim())) {
            s.setPlan("go");
            currentPlan = "go";
            s.setProvider("local-engine");
        }

        if (provider != null && !provider.isBlank()) {
            String norm = normalizeProvider(provider);
            if ("go".equals(currentPlan) && ("gemini".equals(norm) || "openai".equals(norm) || "ollama".equals(norm) || "ollama-cloud".equals(norm))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Custom AI providers (Gemini, ChatGPT, Ollama) require COBUG Plus (₹99/month).");
            }
            s.setProvider(norm);
        } else if ("go".equals(currentPlan)) {
            s.setProvider("local-engine");
        }

        if (model != null && !model.isBlank()) s.setModel(model.trim());
        if (localUrl != null && !localUrl.isBlank()) s.setLocalOllamaUrl(localUrl.trim());
        if (includeProjectContext != null) s.setIncludeProjectContext(includeProjectContext);
        if (includeActivityContext != null) s.setIncludeActivityContext(includeActivityContext);
        if (automaticAssistance != null) s.setAutomaticAssistance(automaticAssistance);
        if (customGeminiKey != null) s.setCustomGeminiKey(customGeminiKey.trim());
        if (customOpenAiKey != null) s.setCustomOpenAiKey(customOpenAiKey.trim());
        if (customOllamaUrl != null && !customOllamaUrl.isBlank()) s.setCustomOllamaUrl(customOllamaUrl.trim());
        if (customOllamaModel != null && !customOllamaModel.isBlank()) s.setCustomOllamaModel(customOllamaModel.trim());
        return repository.save(s);
    }

    public AiSettings activatePlan(String email, String plan) {
        AiSettings s = getOrCreate(email);
        String p = plan == null || plan.isBlank() ? "go" : plan.toLowerCase().trim();
        s.setPlan(p);
        if ("go".equals(p)) {
            s.setProvider("local-engine");
        } else {
            s.setCloudEnabled(true);
            if ("local-engine".equals(s.getProvider())) {
                s.setProvider("gemini");
            }
        }
        return repository.save(s);
    }

    public AiSettings activateCloud(String email) {
        return activatePlan(email, "plus");
    }

    public AiSettings activatePlanFromPayment(String email, String plan, String description) {
        AiSettings s = getOrCreate(email);
        String p = plan == null || plan.isBlank() ? "plus" : plan.toLowerCase().trim();
        s.setPlan(p);
        s.setCloudEnabled(true);
        if ("local-engine".equals(s.getProvider())) {
            s.setProvider("gemini");
            if (s.getModel() == null || s.getModel().isBlank()) s.setModel(defaultGeminiModel);
        }
        return repository.save(s);
    }

    public AiSettings activatePlanFromPayment(String email, String plan, long credits, String description) {
        return activatePlanFromPayment(email, plan, description);
    }

    public AiSettings activateCloudFromPayment(String email, long credits, String description) {
        return activatePlanFromPayment(email, "plus", description);
    }

    public AiSettings prepareRequest(String email) {
        return getOrCreate(email);
    }

    public void consumeCloudCredit(String email) {
        // Plans provide unlimited access for Plus and Pro subscribers. Credit deduction is not used.
    }

    private User user(String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return "ollama";
        String p = provider.trim().toLowerCase();
        if (p.equals("local") || p.equals("ollama-local")) return "ollama";
        if (p.equals("cloud-ollama")) return "ollama-cloud";
        if (p.equals("chatgpt")) return "openai";
        if (p.equals("intent") || p.equals("intent-detector") || p.equals("local-intent")) return "local-engine";
        return p;
    }
}
