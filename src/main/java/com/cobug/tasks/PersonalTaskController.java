package com.cobug.tasks;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/tasks")
public class PersonalTaskController {
    private final PersonalTaskRepository repo;
    private final UserRepository users;

    public PersonalTaskController(PersonalTaskRepository repo, UserRepository users) {
        this.repo = repo;
        this.users = users;
    }

    public record TaskRequest(String title, String description, String status, String priority, LocalDate dueDate) {}

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::map).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody TaskRequest request, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task title is required.");
        }

        PersonalTask task = new PersonalTask();
        task.setUserId(userId);
        task.setTitle(request.title().trim());
        task.setDescription(request.description() == null ? "" : request.description().trim());
        task.setStatus(validStatus(request.status()));
        task.setPriority(validPriority(request.priority()));
        task.setDueDate(request.dueDate());

        return map(repo.save(task));
    }

    @PatchMapping("/{taskId}")
    public Map<String, Object> update(@PathVariable UUID taskId, @RequestBody TaskRequest request, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        PersonalTask task = repo.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found."));

        if (request != null && request.title() != null && !request.title().isBlank()) task.setTitle(request.title().trim());
        if (request != null && request.description() != null) task.setDescription(request.description().trim());
        if (request != null && request.status() != null) task.setStatus(validStatus(request.status()));
        if (request != null && request.priority() != null) task.setPriority(validPriority(request.priority()));
        if (request != null && request.dueDate() != null) task.setDueDate(request.dueDate());
        return map(repo.save(task));
    }

    @PostMapping("/{taskId}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void done(@PathVariable UUID taskId, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        PersonalTask task = repo.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found."));
        repo.delete(task);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID taskId, Authentication authentication) {
        done(taskId, authentication);
    }

    private UUID currentUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        User user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
        return user.getId();
    }

    private String validStatus(String value) {
        String v = value == null ? "TODO" : value.toUpperCase(Locale.ROOT);
        return Set.of("TODO", "IN_PROGRESS", "BLOCKED", "DONE").contains(v) ? v : "TODO";
    }

    private String validPriority(String value) {
        String v = value == null ? "MEDIUM" : value.toUpperCase(Locale.ROOT);
        return Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(v) ? v : "MEDIUM";
    }

    private Map<String, Object> map(PersonalTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId());
        m.put("title", task.getTitle());
        m.put("description", task.getDescription());
        m.put("status", task.getStatus());
        m.put("priority", task.getPriority());
        m.put("dueDate", task.getDueDate());
        m.put("createdAt", task.getCreatedAt());
        m.put("updatedAt", task.getUpdatedAt());
        return m;
    }
}
