package com.cobug.ai;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/settings")
public class AiSettingsController {
    private final AiSettingsService service;

    public AiSettingsController(AiSettingsService service) {
        this.service = service;
    }

    public record UpdateRequest(
            String provider,
            String model,
            String localOllamaUrl,
            Boolean includeProjectContext,
            Boolean includeActivityContext,
            Boolean automaticAssistance,
            String plan,
            String customGeminiKey,
            String customOpenAiKey,
            String customOllamaUrl,
            String customOllamaModel
    ) {}

    @GetMapping
    public Map<String,Object> get(Authentication authentication) {
        return service.dto(authentication.getName());
    }

    @PutMapping
    public Map<String,Object> update(@RequestBody UpdateRequest request, Authentication authentication) {
        UpdateRequest r = request == null ? new UpdateRequest(null,null,null,null,null,null,null,null,null,null,null) : request;
        service.update(authentication.getName(), r.provider(), r.model(), r.localOllamaUrl(),
                r.includeProjectContext(), r.includeActivityContext(), r.automaticAssistance(),
                r.plan(), r.customGeminiKey(), r.customOpenAiKey(), r.customOllamaUrl(), r.customOllamaModel());
        return service.dto(authentication.getName());
    }

    @PostMapping("/cloud/activate")
    public Map<String,Object> activate(Authentication authentication) {
        service.activateCloud(authentication.getName());
        return service.dto(authentication.getName());
    }

    @PostMapping("/plan/activate")
    public Map<String,Object> activatePlan(@RequestParam(name = "plan", defaultValue = "plus") String plan, Authentication authentication) {
        service.activatePlan(authentication.getName(), plan);
        return service.dto(authentication.getName());
    }
}
