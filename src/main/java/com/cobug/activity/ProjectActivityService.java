package com.cobug.activity;

import com.cobug.auth.User;
import com.cobug.projects.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@Service
public class ProjectActivityService {
    private final ProjectActivityRepository repository;
    private final ProjectAccessService access;

    public ProjectActivityService(ProjectActivityRepository repository, ProjectAccessService access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional
    public void record(UUID projectId, User actor, String action, String entityType, String entityId, String details) {
        ProjectActivity item = new ProjectActivity();
        item.setProjectId(projectId);
        item.setActorId(actor.getId());
        item.setActorName(actor.getName());
        item.setActorEmail(actor.getEmail());
        item.setAction(action);
        item.setEntityType(entityType);
        item.setEntityId(entityId);
        item.setDetails(details);
        repository.save(item);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID projectId, String email) {
        access.requireMember(projectId, email);
        return repository.findTop200ByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", a.getId());
                    item.put("actorId", a.getActorId());
                    item.put("actorName", a.getActorName());
                    item.put("actorEmail", a.getActorEmail());
                    item.put("action", a.getAction());
                    item.put("entityType", a.getEntityType());
                    item.put("entityId", a.getEntityId() == null ? "" : a.getEntityId());
                    item.put("details", a.getDetails() == null ? "" : a.getDetails());
                    item.put("createdAt", a.getCreatedAt());
                    return item;
                })
                .toList();
    }
}
