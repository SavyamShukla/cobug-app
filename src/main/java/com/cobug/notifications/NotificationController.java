package com.cobug.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return notifications.list(authentication.getName());
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("count", notifications.unreadCount(authentication.getName()));
    }

    @PatchMapping("/{notificationId}/seen")
    public Map<String, Object> markSeen(
            @PathVariable UUID notificationId,
            Authentication authentication
    ) {
        return notifications.markSeen(authentication.getName(), notificationId);
    }

    @PatchMapping("/seen")
    public Map<String, Object> markAllSeen(Authentication authentication) {
        long updated = notifications.markAllSeen(authentication.getName());
        return Map.of("updated", updated);
    }
}
