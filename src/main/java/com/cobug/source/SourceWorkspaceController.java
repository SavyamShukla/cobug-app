package com.cobug.source;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/source/workspace")
public class SourceWorkspaceController {
    private final SourceWorkspaceService service;
    public SourceWorkspaceController(SourceWorkspaceService service) { this.service = service; }

    @GetMapping
    public Map<String,Object> get(@PathVariable UUID projectId, Authentication a) {
        return service.get(projectId, a.getName());
    }

    @PutMapping
    public Map<String,Object> save(@PathVariable UUID projectId,
                                    @RequestBody SourceWorkspaceService.SaveRequest request,
                                    Authentication a) {
        return service.save(projectId, a.getName(), request);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable UUID projectId, Authentication a) {
        service.clear(projectId, a.getName());
        return ResponseEntity.noContent().build();
    }
}
