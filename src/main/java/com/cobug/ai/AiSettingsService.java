package com.cobug.ai;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.payments.RazorpayPaymentTransaction;
import com.cobug.payments.RazorpayPaymentTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiSettingsService {
    private final AiSettingsRepository repository;
    private final UserRepository users;
    private final AiCreditTransactionRepository transactions;
    private final RazorpayPaymentTransactionRepository paymentTransactions;
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
            RazorpayPaymentTransactionRepository paymentTransactions,
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
        this.paymentTransactions = paymentTransactions;
        this.defaultProvider = normalizeProvider(defaultProvider);
        this.defaultModel = defaultModel == null ? "" : defaultModel.trim();
        this.defaultLocalUrl = defaultLocalUrl == null || defaultLocalUrl.isBlank() ? "http://localhost:11434" : defaultLocalUrl.trim();
        this.defaultCloudModel = defaultCloudModel == null || defaultCloudModel.isBlank() ? "gpt-oss:20b" : defaultCloudModel.trim();
        this.defaultGeminiModel = defaultGeminiModel == null || defaultGeminiModel.isBlank() ? "gemini-3.8-flash" : defaultGeminiModel.trim();
        this.testActivationEnabled = testActivationEnabled;
        this.activationCredits = Math.max(1, activationCredits);
    }

    public String resolveActivePaidPlan(User user) {
        if (paymentTransactions == null || user == null) return null;
        try {
            List<RazorpayPaymentTransaction> paidTxs = paymentTransactions.findByUserAndStatusOrderByPaidAtDesc(user, RazorpayPaymentTransaction.Status.PAID);
            if (paidTxs != null && !paidTxs.isEmpty()) {
                RazorpayPaymentTransaction latest = paidTxs.get(0);
                boolean isPro = latest.getAmount() >= 200 || (latest.getPurpose() != null && latest.getPurpose().toUpperCase().contains("PRO"));
                return isPro ? "pro" : "plus";
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String autoSelectProvider(AiSettings s) {
        if (s.getCustomGeminiKey() != null && !s.getCustomGeminiKey().isBlank()) return "gemini";
        if (s.getCustomGroqKey() != null && !s.getCustomGroqKey().isBlank()) return "groq";
        if (s.getCustomOpenAiKey() != null && !s.getCustomOpenAiKey().isBlank()) return "openai";
        if (s.getCustomOllamaUrl() != null && !s.getCustomOllamaUrl().isBlank() && !"http://localhost:11434".equals(s.getCustomOllamaUrl())) return "ollama";
        return "gemini";
    }

    public AiSettings getOrCreate(String email) {
        User user = user(email);
        AiSettings settings = repository.findByUser(user).orElseGet(() -> {
            AiSettings s = new AiSettings();
            s.setUser(user);
            s.setProvider(defaultProvider);
            s.setModel(defaultModel);
            s.setLocalOllamaUrl(defaultLocalUrl);
            s.setCredits(0);
            return repository.save(s);
        });

        String paidPlan = resolveActivePaidPlan(user);
        if (paidPlan != null) {
            boolean changed = false;
            if ("go".equalsIgnoreCase(settings.getPlan()) || ("plus".equalsIgnoreCase(settings.getPlan()) && "pro".equalsIgnoreCase(paidPlan))) {
                settings.setPlan(paidPlan);
                settings.setCloudEnabled(true);
                changed = true;
            }
            if ("local-engine".equalsIgnoreCase(settings.getProvider()) || settings.getProvider() == null || settings.getProvider().isBlank()) {
                settings.setProvider(autoSelectProvider(settings));
                changed = true;
            }
            if (changed) {
                settings = repository.save(settings);
            }
        }
        return settings;
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
        out.put("customOpenAiBaseUrl", s.getCustomOpenAiBaseUrl() == null ? "https://api.openai.com/v1" : s.getCustomOpenAiBaseUrl());
        out.put("customOpenAiModel", s.getCustomOpenAiModel() == null ? "chatgpt-4o-latest" : s.getCustomOpenAiModel());
        out.put("customOllamaUrl", s.getCustomOllamaUrl() == null ? "http://localhost:11434" : s.getCustomOllamaUrl());
        out.put("customOllamaModel", s.getCustomOllamaModel() == null ? "qwen2.5-coder:3b" : s.getCustomOllamaModel());
        out.put("customGroqKey", s.getCustomGroqKey() == null ? "" : s.getCustomGroqKey());
        out.put("customGroqModel", s.getCustomGroqModel() == null ? "llama-3.3-70b-versatile" : s.getCustomGroqModel());
        out.put("includeProjectContext", s.isIncludeProjectContext());
        out.put("includeActivityContext", s.isIncludeActivityContext());
        out.put("automaticAssistance", s.isAutomaticAssistance());
        out.put("testActivationEnabled", testActivationEnabled);
        out.put("activationCredits", activationCredits);
        out.put("cloudProviders", new String[]{"ollama-cloud", "gemini", "openai", "groq"});
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
        return update(email, provider, model, localUrl, includeProjectContext, includeActivityContext, automaticAssistance, null, null, null, null, null, null, null, null, null);
    }

    public AiSettings update(String email, String provider, String model, String localUrl,
                             Boolean includeProjectContext, Boolean includeActivityContext,
                             Boolean automaticAssistance, String plan,
                             String customGeminiKey, String customOpenAiKey,
                             String customOllamaUrl, String customOllamaModel) {
        return update(email, provider, model, localUrl, includeProjectContext, includeActivityContext, automaticAssistance, plan, customGeminiKey, customOpenAiKey, customOllamaUrl, customOllamaModel, null, null, null, null);
    }

    public AiSettings update(String email, String provider, String model, String localUrl,
                             Boolean includeProjectContext, Boolean includeActivityContext,
                             Boolean automaticAssistance, String plan,
                             String customGeminiKey, String customOpenAiKey,
                             String customOllamaUrl, String customOllamaModel,
                             String customGroqKey, String customGroqModel) {
        return update(email, provider, model, localUrl, includeProjectContext, includeActivityContext, automaticAssistance, plan, customGeminiKey, customOpenAiKey, customOllamaUrl, customOllamaModel, customGroqKey, customGroqModel, null, null);
    }

    public AiSettings update(String email, String provider, String model, String localUrl,
                             Boolean includeProjectContext, Boolean includeActivityContext,
                             Boolean automaticAssistance, String plan,
                             String customGeminiKey, String customOpenAiKey,
                             String customOllamaUrl, String customOllamaModel,
                             String customGroqKey, String customGroqModel,
                             String customOpenAiBaseUrl, String customOpenAiModel) {
        AiSettings s = getOrCreate(email);
        User user = user(email);
        String paidPlan = resolveActivePaidPlan(user);

        if (plan != null && !plan.isBlank()) {
            String target = plan.toLowerCase().trim();
            // Prevent accidental downgrades from stale client localstorage if user has an active paid subscription
            if ("go".equals(target) && paidPlan != null) {
                s.setPlan(paidPlan);
            } else {
                s.setPlan(target);
            }
        } else if (paidPlan != null && "go".equalsIgnoreCase(s.getPlan())) {
            s.setPlan(paidPlan);
        }

        String effectivePlan = s.getPlan() == null || s.getPlan().isBlank() ? "go" : s.getPlan().toLowerCase().trim();

        if (provider != null && !provider.isBlank()) {
            String norm = normalizeProvider(provider);
            if (!"local-engine".equals(norm) && "go".equals(effectivePlan)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "COBUG Go plan only supports the Local Intent Engine. Upgrade to Plus (₹1/month) or Pro (₹2/month) to connect " + norm + ".");
            }
            s.setProvider(norm);
        } else if ("go".equals(effectivePlan)) {
            s.setProvider("local-engine");
        } else if ("local-engine".equals(s.getProvider())) {
            s.setProvider(autoSelectProvider(s));
        }

        if (model != null && !model.isBlank()) s.setModel(model.trim());
        if (localUrl != null && !localUrl.isBlank()) s.setLocalOllamaUrl(localUrl.trim());
        if (includeProjectContext != null) s.setIncludeProjectContext(includeProjectContext);
        if (includeActivityContext != null) s.setIncludeActivityContext(includeActivityContext);
        if (automaticAssistance != null) s.setAutomaticAssistance(automaticAssistance);
        if (customGeminiKey != null) s.setCustomGeminiKey(customGeminiKey.trim());
        if (customOpenAiKey != null) s.setCustomOpenAiKey(customOpenAiKey.trim());
        if (customOpenAiBaseUrl != null && !customOpenAiBaseUrl.isBlank()) s.setCustomOpenAiBaseUrl(customOpenAiBaseUrl.trim());
        if (customOpenAiModel != null && !customOpenAiModel.isBlank()) s.setCustomOpenAiModel(customOpenAiModel.trim());
        if (customOllamaUrl != null && !customOllamaUrl.isBlank()) s.setCustomOllamaUrl(customOllamaUrl.trim());
        if (customOllamaModel != null && !customOllamaModel.isBlank()) s.setCustomOllamaModel(customOllamaModel.trim());
        if (customGroqKey != null) s.setCustomGroqKey(customGroqKey.trim());
        if (customGroqModel != null && !customGroqModel.isBlank()) s.setCustomGroqModel(customGroqModel.trim());
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
                s.setProvider(autoSelectProvider(s));
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
            s.setProvider(autoSelectProvider(s));
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
        if (p.equals("groq") || p.equals("groq-cloud")) return "groq";
        if (p.equals("intent") || p.equals("intent-detector") || p.equals("local-intent")) return "local-engine";
        return p;
    }
}
