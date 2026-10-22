package com.cobug.activity;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/activity")
public class ProjectActivityController {
    private final ProjectActivityService service;
    public ProjectActivityController(ProjectActivityService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID projectId, Authentication authentication) {
        return service.list(projectId, authentication.getName());
    }
}
