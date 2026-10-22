package com.cobug.notifications;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.projects.InvitationStatus;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectInvitation;
import com.cobug.projects.ProjectInvitationRepository;
import com.cobug.projects.ProjectRepository;
import com.cobug.social.FriendRequest;
import com.cobug.social.FriendRequestRepository;
import com.cobug.social.FriendRequestStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final FriendRequestRepository friendRequests;
    private final ProjectInvitationRepository invitations;
    private final ProjectRepository projects;

    public NotificationService(
            NotificationRepository notifications,
            UserRepository users,
            FriendRequestRepository friendRequests,
            ProjectInvitationRepository invitations,
            ProjectRepository projects
    ) {
        this.notifications = notifications;
        this.users = users;
        this.friendRequests = friendRequests;
        this.invitations = invitations;
        this.projects = projects;
    }

    @Transactional
    public Notification create(
            UUID recipientId,
            UUID actorId,
            String kind,
            String title,
            String message,
            UUID referenceId,
            String referenceType
    ) {
        if (recipientId == null) {
            throw new IllegalArgumentException("Notification recipient cannot be null.");
        }

        Notification notification = new Notification();
        notification.setRecipientId(recipientId);
        notification.setActorId(actorId);
        notification.setKind(kind);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setReferenceId(referenceId);
        notification.setReferenceType(referenceType);
        notification.setSeen(false);
        notification.setCreatedAt(Instant.now());

        return notifications.save(notification);
    }

    @Transactional
    public List<Map<String, Object>> list(String email) {
        UUID userId = currentUserId(email);
        ensurePendingNotifications(userId);
        return notifications.findByRecipientIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }


    /**
     * Backfills notifications for pending requests/invitations that existed
     * before the persistent notification system was introduced.
     */
    private void ensurePendingNotifications(UUID userId) {
        users.findById(userId).orElseThrow();

        for (FriendRequest request : friendRequests
                .findByRecipientIdAndStatusOrderByCreatedAtDesc(userId, FriendRequestStatus.PENDING)) {
            if (notifications.existsByRecipientIdAndReferenceIdAndKind(
                    userId, request.getId(), "FRIEND_REQUEST_RECEIVED")) {
                continue;
            }

            User requester = users.findById(request.getRequesterId()).orElse(null);
            String requesterName = requester == null ? "Someone" : requester.getName();
            create(
                    userId,
                    request.getRequesterId(),
                    "FRIEND_REQUEST_RECEIVED",
                    "New friend request",
                    requesterName + " wants to connect with you on COBUG.",
                    request.getId(),
                    "FRIEND_REQUEST"
            );
        }

        for (ProjectInvitation invitation : invitations
                .findByInvitedUserIdAndStatusOrderByCreatedAtDesc(userId, InvitationStatus.PENDING)) {
            if (notifications.existsByRecipientIdAndReferenceIdAndKind(
                    userId, invitation.getId(), "INVITATION_RECEIVED")) {
                continue;
            }

            Project project = projects.findById(invitation.getProjectId()).orElse(null);
            User owner = project == null ? null : project.getOwner();
            String ownerName = owner == null ? "A project owner" : owner.getName();
            String projectName = project == null ? "a COBUG project" : project.getName();

            create(
                    userId,
                    owner == null ? null : owner.getId(),
                    "INVITATION_RECEIVED",
                    "Project invitation",
                    ownerName + " invited you to join " + projectName + " as " + invitation.getRole().name() + ".",
                    invitation.getId(),
                    "PROJECT_INVITATION"
            );
        }
    }

    @Transactional
    public long unreadCount(String email) {
        UUID userId = currentUserId(email);
        ensurePendingNotifications(userId);
        return notifications.countByRecipientIdAndSeenFalse(userId);
    }

    @Transactional
    public Map<String, Object> markSeen(String email, UUID notificationId) {
        UUID userId = currentUserId(email);
        Notification notification = notifications.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Notification not found."
                ));

        if (!notification.isSeen()) {
            notification.setSeen(true);
            notification.setSeenAt(Instant.now());
            notifications.save(notification);
        }

        return toResponse(notification);
    }

    /**
     * Converts the original actionable notification into its completed state.
     * This keeps the notification history correct after an invitation/friend
     * request is accepted or rejected and prevents the action button from
     * reappearing after a refresh.
     */
    @Transactional
    public void completeActionNotification(
            UUID recipientId,
            UUID referenceId,
            String pendingKind,
            String completedKind,
            String title,
            String message
    ) {
        if (recipientId == null || referenceId == null) return;

        notifications.findFirstByRecipientIdAndReferenceIdAndKind(
                recipientId, referenceId, pendingKind
        ).ifPresent(notification -> {
            notification.setKind(completedKind);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setSeen(true);
            notification.setSeenAt(notification.getSeenAt() == null ? Instant.now() : notification.getSeenAt());
            notifications.save(notification);
        });
    }

    @Transactional
    public long markAllSeen(String email) {
        UUID userId = currentUserId(email);
        List<Notification> unseen = notifications.findByRecipientIdOrderByCreatedAtDesc(userId)
                .stream()
                .filter(notification -> !notification.isSeen())
                .toList();

        if (unseen.isEmpty()) return 0;

        Instant now = Instant.now();
        unseen.forEach(notification -> {
            notification.setSeen(true);
            notification.setSeenAt(now);
        });
        notifications.saveAll(unseen);
        return unseen.size();
    }

    private Map<String, Object> toResponse(Notification notification) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", notification.getId());
        out.put("recipientId", notification.getRecipientId());
        out.put("actorId", notification.getActorId());
        out.put("kind", notification.getKind());
        out.put("title", notification.getTitle());
        out.put("message", notification.getMessage());
        out.put("referenceId", notification.getReferenceId());
        out.put("referenceType", notification.getReferenceType());
        out.put("seen", notification.isSeen());
        out.put("seenAt", notification.getSeenAt());
        out.put("createdAt", notification.getCreatedAt());

        if (notification.getActorId() != null) {
            users.findById(notification.getActorId()).ifPresent(actor -> {
                out.put("actorName", actor.getName());
                out.put("actorEmail", actor.getEmail());
            });
        }

        return out;
    }

    private UUID currentUserId(String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ))
                .getId();
    }
}
