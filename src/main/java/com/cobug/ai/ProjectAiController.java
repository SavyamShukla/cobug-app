package com.cobug.ai;

import com.cobug.auth.User;
import com.cobug.projects.ProjectAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/ai")
public class ProjectAiController {
    private final ProjectAccessService access;
    private final AiIntentRoutingService routing;
    private final AiSettingsService settings;
    private final AiProvider ai;

    public ProjectAiController(ProjectAccessService access, AiIntentRoutingService routing,
                               AiSettingsService settings, AiProvider ai) {
        this.access = access;
        this.routing = routing;
        this.settings = settings;
        this.ai = ai;
    }

    public record AskRequest(String message, List<Map<String, Object>> history, String detectedIntent, Double confidence) {}

    @PostMapping("/chat")
    public Map<String,Object> chat(@PathVariable UUID projectId, @RequestBody AskRequest request, Authentication authentication) {
        User user = access.requireMember(projectId, authentication.getName());
        String q = request == null || request.message() == null ? "" : request.message().trim();
        if (q.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Message cannot be empty.");
        String clientIntent = request == null ? null : request.detectedIntent();
        Double clientConfidence = request == null ? null : request.confidence();
        AiIntentRoutingService.RoutedAnswer answer = routing.answerProject(
                projectId, user.getEmail(), q, normalizeHistory(request == null ? null : request.history()),
                clientIntent, clientConfidence);
        Map<String,Object> userSettings = settings.dto(user.getEmail());
        String plan = userSettings.get("plan") == null ? "go" : String.valueOf(userSettings.get("plan")).toLowerCase().trim();
        boolean isGoPlan = "go".equals(plan);
        boolean isLocal = "LOCAL".equals(answer.route());

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("reply", answer.reply());
        if (isLocal) {
            out.put("mode", "local-engine");
            out.put("model", "Local Operator (" + (answer.intent() != null ? answer.intent() : "Deterministic") + ")");
        } else if (isGoPlan) {
            out.put("mode", "COBUG Go");
            out.put("model", "");
        } else {
            out.put("mode", userSettings.get("provider"));
            out.put("model", userSettings.get("model"));
        }
        out.put("user", user.getName());
        out.put("intent", answer.intent());
        out.put("confidence", answer.confidence());
        out.put("route", answer.route());
        out.put("routingDetail", answer.detail());
        return out;
    }

    @GetMapping("/test")
    public Map<String,Object> test(@PathVariable UUID projectId, Authentication authentication) {
        User user = access.requireMember(projectId, authentication.getName());
        String reply = ai.answer(projectId, user.getEmail(), "Reply with exactly: COBUG AI CONNECTION SUCCESS");
        return Map.of("status", "connected", "provider", ai.name(), "model", ai.model(), "reply", reply);
    }

    private List<Map<String,String>> normalizeHistory(List<Map<String,Object>> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<Map<String,String>> result = new ArrayList<>();
        for (Map<String,Object> item : history) {
            if (item == null) continue;
            Object role = item.get("role");
            Object content = item.get("content");
            if (content == null) content = item.get("text");
            if (content instanceof Map<?,?> nested) {
                Object nestedContent = nested.get("content");
                if (nestedContent == null) nestedContent = nested.get("text");
                content = nestedContent == null ? nested.toString() : nestedContent;
            }
            result.add(Map.of("role", role == null ? "user" : String.valueOf(role),
                    "content", content == null ? "" : String.valueOf(content)));
        }
        return result;
    }
}
