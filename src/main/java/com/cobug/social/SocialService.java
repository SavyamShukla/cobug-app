package com.cobug.social;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.notifications.NotificationService;
import com.cobug.projects.ProjectMember;
import com.cobug.projects.ProjectMemberRepository;
import com.cobug.projects.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class SocialService {
    private final UserRepository users;
    private final FriendRequestRepository requests;
    private final DirectMessageRepository messages;
    private final NotificationService notifications;
    private final UserBlockRepository blocks;
    private final ProjectMemberRepository projectMembers;
    private final ProjectRepository projects;

    public SocialService(
            UserRepository users,
            FriendRequestRepository requests,
            DirectMessageRepository messages,
            NotificationService notifications,
            UserBlockRepository blocks,
            ProjectMemberRepository projectMembers,
            ProjectRepository projects
    ) {
        this.users = users;
        this.requests = requests;
        this.messages = messages;
        this.notifications = notifications;
        this.blocks = blocks;
        this.projectMembers = projectMembers;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> searchUsers(String email, String query) {
        User me = user(email);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) return List.of();
        return users.findAll().stream()
                .filter(u -> !u.getId().equals(me.getId()))
                .filter(u -> !isBlockedFromView(me.getId(), u.getId()))
                .filter(u -> u.getName().toLowerCase(Locale.ROOT).contains(q) || u.getEmail().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(30)
                .map(u -> userSummary(me, u))
                .toList();
    }

    @Transactional
    public Map<String,Object> sendRequest(String email, UUID recipientId) {
        User me = user(email);
        if (me.getId().equals(recipientId)) throw bad("You cannot send a friend request to yourself.");
        User recipient = users.findById(recipientId).orElseThrow(() -> notFound("User not found."));
        if (blocks.existsByBlockerIdAndBlockedId(recipient.getId(), me.getId())) throw notFound("User not found.");
        if (blocks.existsByBlockerIdAndBlockedId(me.getId(), recipient.getId())) throw conflict("You have blocked this user. Unblock them before sending a request.");

        FriendRequest existing = findRelationship(me.getId(), recipient.getId()).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == FriendRequestStatus.ACCEPTED) throw conflict("You are already friends.");
            if (existing.getStatus() == FriendRequestStatus.PENDING) throw conflict("A friend request is already pending.");
            // Re-open a previously rejected request in the same direction.
            if (existing.getRequesterId().equals(me.getId())) {
                existing.setStatus(FriendRequestStatus.PENDING);
                FriendRequest saved = requests.save(existing);
                notifications.create(
                        recipient.getId(),
                        me.getId(),
                        "FRIEND_REQUEST_RECEIVED",
                        "New friend request",
                        me.getName() + " wants to connect with you on COBUG.",
                        saved.getId(),
                        "FRIEND_REQUEST"
                );
                return requestSummary(saved, me);
            }
        }

        // If the other user has a pending request to us, accepting it is the natural action.
        Optional<FriendRequest> reverse = requests.findByRequesterIdAndRecipientId(recipient.getId(), me.getId());
        if (reverse.isPresent() && reverse.get().getStatus() == FriendRequestStatus.PENDING) {
            reverse.get().setStatus(FriendRequestStatus.ACCEPTED);
            FriendRequest saved = requests.save(reverse.get());
            notifications.create(
                    saved.getRequesterId(),
                    me.getId(),
                    "FRIEND_REQUEST_ACCEPTED",
                    "Friend request accepted",
                    me.getName() + " accepted your friend request.",
                    saved.getId(),
                    "FRIEND_REQUEST"
            );
            return requestSummary(saved, me);
        }

        FriendRequest request = new FriendRequest();
        request.setRequesterId(me.getId());
        request.setRecipientId(recipient.getId());
        request.setStatus(FriendRequestStatus.PENDING);
        FriendRequest saved = requests.save(request);
        notifications.create(
                recipient.getId(),
                me.getId(),
                "FRIEND_REQUEST_RECEIVED",
                "New friend request",
                me.getName() + " wants to connect with you on COBUG.",
                saved.getId(),
                "FRIEND_REQUEST"
        );
        return requestSummary(saved, me);
    }

    @Transactional
    public Map<String,Object> respond(String email, UUID requestId, boolean accept) {
        User me = user(email);
        FriendRequest request = requests.findById(requestId).orElseThrow(() -> notFound("Friend request not found."));
        if (!request.getRecipientId().equals(me.getId())) throw forbidden("You can respond only to requests sent to you.");
        if (request.getStatus() != FriendRequestStatus.PENDING) throw conflict("This request is no longer pending.");
        request.setStatus(accept ? FriendRequestStatus.ACCEPTED : FriendRequestStatus.REJECTED);
        FriendRequest saved = requests.save(request);

        String kind = accept
                ? "FRIEND_REQUEST_ACCEPTED"
                : "FRIEND_REQUEST_REJECTED";
        String title = accept
                ? "Friend request accepted"
                : "Friend request rejected";
        String message = accept
                ? me.getName() + " accepted your friend request."
                : me.getName() + " rejected your friend request.";

        notifications.create(
                saved.getRequesterId(),
                me.getId(),
                kind,
                title,
                message,
                saved.getId(),
                "FRIEND_REQUEST"
        );

        notifications.completeActionNotification(
                me.getId(),
                saved.getId(),
                "FRIEND_REQUEST_RECEIVED",
                kind,
                title,
                accept
                        ? "You accepted " + users.findById(saved.getRequesterId()).map(User::getName).orElse("this friend request") + "'s friend request."
                        : "You rejected " + users.findById(saved.getRequesterId()).map(User::getName).orElse("this friend request") + "'s friend request."
        );

        return requestSummary(saved, me);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> received(String email) {
        User me = user(email);
        return requests.findByRecipientIdAndStatusOrderByCreatedAtDesc(me.getId(), FriendRequestStatus.PENDING)
                .stream().map(r -> requestSummary(r, me)).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> sent(String email) {
        User me = user(email);
        return requests.findByRequesterIdAndStatusOrderByCreatedAtDesc(me.getId(), FriendRequestStatus.PENDING)
                .stream().map(r -> requestSummary(r, me)).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> friends(String email) {
        User me = user(email);
        return requests.findByRequesterIdOrRecipientIdOrderByUpdatedAtDesc(me.getId(), me.getId()).stream()
                .filter(r -> r.getStatus() == FriendRequestStatus.ACCEPTED)
                .map(r -> users.findById(otherId(r, me.getId())).orElse(null))
                .filter(Objects::nonNull)
                .filter(u -> !isBlockedFromView(me.getId(), u.getId()))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> userSummary(me, u))
                .toList();
    }

    @Transactional
    public Map<String,Object> unfriend(String email, UUID friendId) {
        User me = user(email);
        User friend = users.findById(friendId).orElseThrow(() -> notFound("User not found."));
        if (!areFriends(me.getId(), friend.getId())) throw conflict("You are not friends with this user.");
        requests.findByRequesterIdAndRecipientId(me.getId(), friend.getId()).ifPresent(requests::delete);
        requests.findByRequesterIdAndRecipientId(friend.getId(), me.getId()).ifPresent(requests::delete);
        return Map.of("ok", true, "userId", friendId, "relationshipStatus", "NONE");
    }

    @Transactional(readOnly = true)
    public Map<String,Object> sharedProjects(String email, UUID targetId) {
        User me = user(email);
        User target = users.findById(targetId).orElseThrow(() -> notFound("User not found."));
        if (me.getId().equals(target.getId())) throw bad("You cannot block yourself.");
        List<Map<String,Object>> shared = projectMembers.findByUserIdOrderByJoinedAtAsc(me.getId()).stream()
                .map(ProjectMember::getProjectId)
                .filter(pid -> projectMembers.existsByProjectIdAndUserId(pid, target.getId()))
                .distinct()
                .map(pid -> projects.findById(pid).map(project -> {
                    Map<String,Object> m = new LinkedHashMap<>();
                    m.put("id", project.getId()); m.put("name", project.getName()); return m;
                }).orElse(null))
                .filter(Objects::nonNull).toList();
        return Map.of("sharedProjects", shared);
    }

    @Transactional
    public Map<String,Object> block(String email, UUID targetId) {
        User me = user(email);
        if (me.getId().equals(targetId)) throw bad("You cannot block yourself.");
        User target = users.findById(targetId).orElseThrow(() -> notFound("User not found."));
        UserBlock existing = blocks.findByBlockerIdAndBlockedId(me.getId(), target.getId()).orElse(null);
        if (existing == null) {
            UserBlock b = new UserBlock();
            b.setBlockerId(me.getId());
            b.setBlockedId(target.getId());
            b.setCreatedAt(java.time.Instant.now());
            blocks.save(b);
        }
        requests.findByRequesterIdAndRecipientId(me.getId(), target.getId()).ifPresent(requests::delete);
        requests.findByRequesterIdAndRecipientId(target.getId(), me.getId()).ifPresent(requests::delete);
        List<Map<String,Object>> shared = projectMembers.findByUserIdOrderByJoinedAtAsc(me.getId()).stream()
                .map(ProjectMember::getProjectId)
                .filter(pid -> projectMembers.existsByProjectIdAndUserId(pid, target.getId()))
                .distinct()
                .map(pid -> projects.findById(pid).map(project -> {
                    Map<String,Object> m = new LinkedHashMap<>();
                    m.put("id", project.getId()); m.put("name", project.getName()); return m;
                }).orElse(null))
                .filter(Objects::nonNull).toList();
        return Map.of("ok", true, "userId", targetId, "sharedProjects", shared);
    }

    @Transactional
    public Map<String,Object> unblock(String email, UUID targetId) {
        User me = user(email);
        blocks.findByBlockerIdAndBlockedId(me.getId(), targetId).ifPresent(blocks::delete);
        return Map.of("ok", true, "userId", targetId);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> blocked(String email) {
        User me = user(email);
        return blocks.findByBlockerIdOrBlockedId(me.getId(), me.getId()).stream()
                .filter(b -> b.getBlockerId().equals(me.getId()))
                .map(b -> users.findById(b.getBlockedId()).orElse(null))
                .filter(Objects::nonNull).map(u -> userSummary(me,u)).toList();
    }

    private boolean isBlockedFromView(UUID viewerId, UUID otherId) {
        return blocks.existsByBlockerIdAndBlockedId(otherId, viewerId);
    }

    @Transactional(readOnly = true)
    public boolean areFriends(UUID a, UUID b) {
        return findRelationship(a,b).map(r -> r.getStatus() == FriendRequestStatus.ACCEPTED).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> chat(String email, UUID friendId) {
        User me = user(email);
        User friend = users.findById(friendId).orElseThrow(() -> notFound("User not found."));
        if (!areFriends(me.getId(), friend.getId())) throw forbidden("You can chat only with accepted friends.");
        if (blocks.existsByBlockerIdAndBlockedId(friend.getId(), me.getId()) || blocks.existsByBlockerIdAndBlockedId(me.getId(), friend.getId())) throw forbidden("You cannot message a blocked user.");
        List<DirectMessage> rows = messages.findConversation(me.getId(), friend.getId(), org.springframework.data.domain.PageRequest.of(0, 100));
        List<DirectMessage> copy = new ArrayList<>(rows);
        Collections.reverse(copy);
        return copy.stream().map(m -> messageMap(m, me.getId())).toList();
    }

    @Transactional
    public Map<String,Object> sendMessage(String email, UUID friendId, String content) {
        User me = user(email);
        User friend = users.findById(friendId).orElseThrow(() -> notFound("User not found."));
        if (!areFriends(me.getId(), friend.getId())) throw forbidden("You can chat only with accepted friends.");
        if (blocks.existsByBlockerIdAndBlockedId(friend.getId(), me.getId()) || blocks.existsByBlockerIdAndBlockedId(me.getId(), friend.getId())) throw forbidden("You cannot message a blocked user.");
        String clean = content == null ? "" : content.trim();
        if (clean.isBlank()) throw bad("Message cannot be empty.");
        if (clean.length() > 4000) throw bad("Message is too long (max 4000 characters).");
        DirectMessage message = new DirectMessage();
        message.setSenderId(me.getId());
        message.setRecipientId(friend.getId());
        message.setContent(clean);
        return messageMap(messages.save(message), me.getId());
    }

    private Optional<FriendRequest> findRelationship(UUID a, UUID b) {
        Optional<FriendRequest> direct = requests.findByRequesterIdAndRecipientId(a,b);
        if (direct.isPresent()) return direct;
        return requests.findByRequesterIdAndRecipientId(b,a);
    }

    private UUID otherId(FriendRequest r, UUID me) { return r.getRequesterId().equals(me) ? r.getRecipientId() : r.getRequesterId(); }

    private Map<String,Object> userSummary(User me, User other) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", other.getId()); m.put("name", other.getName()); m.put("email", other.getEmail()); m.put("role", other.getRole().name());
        if (blocks.existsByBlockerIdAndBlockedId(me.getId(), other.getId())) {
            m.put("relationshipStatus", "BLOCKED");
            m.put("requestDirection", "SENT");
            return m;
        }
        findRelationship(me.getId(), other.getId()).ifPresent(r -> {
            m.put("relationshipStatus", r.getStatus().name());
            m.put("requestId", r.getId());
            m.put("requestDirection", r.getRequesterId().equals(me.getId()) ? "SENT" : "RECEIVED");
        });
        if (!m.containsKey("relationshipStatus")) m.put("relationshipStatus", "NONE");
        return m;
    }

    private Map<String,Object> requestSummary(FriendRequest r, User viewer) {
        UUID otherId = r.getRequesterId().equals(viewer.getId()) ? r.getRecipientId() : r.getRequesterId();
        User other = users.findById(otherId).orElse(null);
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("status", r.getStatus().name()); m.put("direction", r.getRequesterId().equals(viewer.getId()) ? "SENT" : "RECEIVED");
        if (other != null) { m.put("userId", other.getId()); m.put("name", other.getName()); m.put("email", other.getEmail()); m.put("role", other.getRole().name()); }
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<String,Object> messageMap(DirectMessage m, UUID currentUserId) {
        Map<String,Object> out = new LinkedHashMap<>();
        User sender = users.findById(m.getSenderId()).orElse(null);
        out.put("id", m.getId());
        out.put("senderId", m.getSenderId());
        out.put("senderName", sender == null ? "User" : sender.getName());
        out.put("isMine", m.getSenderId().equals(currentUserId));
        out.put("content", m.getContent());
        out.put("createdAt", m.getCreatedAt());
        return out;
    }

    private User user(String email) { return users.findByEmail(email).orElseThrow(() -> notFound("User not found.")); }
    private ResponseStatusException bad(String s) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, s); }
    private ResponseStatusException notFound(String s) { return new ResponseStatusException(HttpStatus.NOT_FOUND, s); }
    private ResponseStatusException conflict(String s) { return new ResponseStatusException(HttpStatus.CONFLICT, s); }
    private ResponseStatusException forbidden(String s) { return new ResponseStatusException(HttpStatus.FORBIDDEN, s); }
}
