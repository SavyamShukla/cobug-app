package com.cobug.ai;

import com.cobug.auth.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_settings")
public class AiSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 32)
    private String provider = "ollama";

    @Column(nullable = false, length = 160)
    private String model = "";

    @Column(length = 500)
    private String localOllamaUrl = "http://localhost:11434";

    @Column(nullable = false)
    private boolean cloudEnabled = false;

    @Column(nullable = false)
    private long credits = 0;

    @Column(nullable = false)
    private boolean includeProjectContext = true;

    @Column(nullable = false)
    private boolean includeActivityContext = true;

    @Column(nullable = false)
    private boolean automaticAssistance = false;

    @Column(length = 32)
    private String plan = "go";

    @Column(length = 255)
    private String customGeminiKey = "";

    @Column(length = 255)
    private String customOpenAiKey = "";

    @Column(length = 255)
    private String customOllamaUrl = "http://localhost:11434";

    @Column(length = 160)
    private String customOllamaModel = "qwen2.5-coder:3b";

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public AiSettings() {}

    public void touch() { updatedAt = LocalDateTime.now(); }

    @PrePersist
    protected void onCreate() {
        if (provider == null || provider.isBlank()) provider = "ollama";
        if (model == null) model = "";
        if (localOllamaUrl == null || localOllamaUrl.isBlank()) localOllamaUrl = "http://localhost:11434";
        if (plan == null || plan.isBlank()) plan = "go";
        if (customOllamaUrl == null || customOllamaUrl.isBlank()) customOllamaUrl = "http://localhost:11434";
        if (customOllamaModel == null || customOllamaModel.isBlank()) customOllamaModel = "qwen2.5-coder:3b";
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getLocalOllamaUrl() { return localOllamaUrl; }
    public void setLocalOllamaUrl(String localOllamaUrl) { this.localOllamaUrl = localOllamaUrl; }
    public boolean isCloudEnabled() { return cloudEnabled; }
    public void setCloudEnabled(boolean cloudEnabled) { this.cloudEnabled = cloudEnabled; }
    public long getCredits() { return credits; }
    public void setCredits(long credits) { this.credits = Math.max(0, credits); }
    public boolean isIncludeProjectContext() { return includeProjectContext; }
    public void setIncludeProjectContext(boolean value) { this.includeProjectContext = value; }
    public boolean isIncludeActivityContext() { return includeActivityContext; }
    public void setIncludeActivityContext(boolean value) { this.includeActivityContext = value; }
    public boolean isAutomaticAssistance() { return automaticAssistance; }
    public void setAutomaticAssistance(boolean value) { this.automaticAssistance = value; }
    public String getPlan() { return plan == null || plan.isBlank() ? "go" : plan; }
    public void setPlan(String plan) { this.plan = plan == null || plan.isBlank() ? "go" : plan.toLowerCase().trim(); }
    public String getCustomGeminiKey() { return customGeminiKey; }
    public void setCustomGeminiKey(String customGeminiKey) { this.customGeminiKey = customGeminiKey; }
    public String getCustomOpenAiKey() { return customOpenAiKey; }
    public void setCustomOpenAiKey(String customOpenAiKey) { this.customOpenAiKey = customOpenAiKey; }
    public String getCustomOllamaUrl() { return customOllamaUrl; }
    public void setCustomOllamaUrl(String customOllamaUrl) { this.customOllamaUrl = customOllamaUrl; }
    public String getCustomOllamaModel() { return customOllamaModel; }
    public void setCustomOllamaModel(String customOllamaModel) { this.customOllamaModel = customOllamaModel; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
