package com.cobug.chat;

import com.cobug.activity.ProjectActivityService;
import com.cobug.auth.User;
import com.cobug.projects.ProjectAccessService;
import com.cobug.ai.AiIntentRoutingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class ProjectChatService {

    private static final Logger log = LoggerFactory.getLogger(ProjectChatService.class);

    private static final UUID AI_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String AI_NAME = "COBUG AI";
    private static final String AI_EMAIL = "ai@cobug.local";

    private static final Pattern AI_MENTION =
            Pattern.compile("(?i)(?<![\\w.])@ai(?![\\w.])");

    public static boolean containsAiMention(String text) {
        return text != null && AI_MENTION.matcher(text).find();
    }

    private final ProjectChatMessageRepository repository;
    private final ProjectAccessService access;
    private final ProjectActivityService activity;
    private final AiIntentRoutingService aiRouting;

    public ProjectChatService(
            ProjectChatMessageRepository repository,
            ProjectAccessService access,
            ProjectActivityService activity,
            AiIntentRoutingService aiRouting
    ) {
        this.repository = repository;
        this.access = access;
        this.activity = activity;
        this.aiRouting = aiRouting;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID projectId, String email) {
        access.requireMember(projectId, email);

        List<ProjectChatMessage> messages = new ArrayList<>(
                repository.findTop100ByProjectIdOrderByCreatedAtDesc(projectId)
        );

        Collections.reverse(messages);

        return messages.stream()
                .map(this::toMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> send(
            UUID projectId,
            String email,
            String content
    ) {
        return send(projectId, email, content, null, null);
    }

    @Transactional
    public Map<String, Object> send(
            UUID projectId,
            String email,
            String content,
            String clientIntent,
            Double clientConfidence
    ) {
        User user = access.requireMember(projectId, email);
        String clean = validateContent(content);

        ProjectChatMessage saved = saveUserMessage(projectId, user, clean);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", toMap(saved));

        if (containsAiMention(clean)) {
            ProjectChatMessage ai = createAiReply(projectId, user, clean, clientIntent, clientConfidence);
            response.put("aiMessage", toMap(ai));
        }

        return response;
    }

    @Transactional
    public ProjectChatMessage saveUserMessage(
            UUID projectId,
            User user,
            String content
    ) {
        String clean = validateContent(content);

        ProjectChatMessage message = new ProjectChatMessage();
        message.setProjectId(projectId);
        message.setSenderId(user.getId());
        message.setSenderName(user.getName());
        message.setSenderEmail(user.getEmail());
        message.setContent(clean);
        message.setAiGenerated(false);

        ProjectChatMessage saved = repository.save(message);

        activity.record(
                projectId,
                user,
                "SENT_MESSAGE",
                "CHAT",
                saved.getId().toString(),
                "Sent a project chat message"
        );

        return saved;
    }

    public ProjectChatMessage saveUserMessage(
            UUID projectId,
            String email,
            String content
    ) {
        User user = access.requireMember(projectId, email);
        return saveUserMessage(projectId, user, content);
    }

    @Transactional
    public ProjectChatMessage generateAiReply(
            UUID projectId,
            String email,
            String prompt
    ) {
        return generateAiReply(projectId, email, prompt, null, null);
    }

    @Transactional
    public ProjectChatMessage generateAiReply(
            UUID projectId,
            String email,
            String prompt,
            String clientIntent,
            Double clientConfidence
    ) {
        User user = access.requireMember(projectId, email);
        return createAiReply(projectId, user, prompt, clientIntent, clientConfidence);
    }

    /**
     * Routes project-chat questions through AiIntentRoutingService.
     *
     * The routing service uses the trained intent detector and local intent
     * engine when the intent is supported. Otherwise, it can use the
     * configured external AI provider as a fallback.
     */
    private ProjectChatMessage createAiReply(
            UUID projectId,
            User user,
            String prompt,
            String clientIntent,
            Double clientConfidence
    ) {
        String reply;

        try {
            // Remove @AI before passing the question to the intent detector.
            String question = prompt == null
                    ? ""
                    : AI_MENTION.matcher(prompt).replaceAll("").trim();
            question = question.replaceAll("^[,:;\\s]+", "").replaceAll("[,:;\\s]+$", "").trim();

            AiIntentRoutingService.RoutedAnswer routed =
                    aiRouting.answerProject(
                            projectId,
                            user.getEmail(),
                            question,
                            List.of(),
                            clientIntent,
                            clientConfidence
                    );

            reply = routed.reply();

            log.info(
                    "[CHAT AI] project={} intent={} confidence={} route={} detail={}",
                    projectId,
                    routed.intent(),
                    routed.confidence(),
                    routed.route(),
                    routed.detail()
            );

        } catch (Exception ex) {
            log.error(
                    "[CHAT AI] Routed answer failed for project {}: {}",
                    projectId,
                    ex.getMessage(),
                    ex
            );

            reply = "COBUG AI could not complete the request right now. "
                    + "Your message was saved successfully. "
                    + "Check the backend logs for the routing/provider error.";
        }

        ProjectChatMessage message = new ProjectChatMessage();
        message.setProjectId(projectId);
        message.setSenderId(AI_USER_ID);
        message.setSenderName(AI_NAME);
        message.setSenderEmail(AI_EMAIL);
        message.setContent(reply);
        message.setAiGenerated(true);

        return repository.save(message);
    }

    private String validateContent(String content) {
        String clean = content == null ? "" : content.trim();

        if (clean.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message cannot be empty."
            );
        }

        if (clean.length() > 4000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message is too long (max 4000 characters)."
            );
        }

        return clean;
    }

    @Transactional
    public void delete(
            UUID projectId,
            UUID messageId,
            String email
    ) {
        User user = access.requireMember(projectId, email);

        ProjectChatMessage message = repository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Message not found."
                ));

        if (!message.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Message not found."
            );
        }

        if (message.isAiGenerated()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "COBUG AI messages cannot be deleted."
            );
        }

        if (!message.getSenderId().equals(user.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You can delete only your own messages."
            );
        }

        repository.delete(message);

        activity.record(
                projectId,
                user,
                "DELETED_MESSAGE",
                "CHAT",
                messageId.toString(),
                "Deleted a project chat message"
        );
    }

    private Map<String, Object> toMap(ProjectChatMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("id", message.getId());
        map.put("senderId", message.getSenderId());
        map.put("senderName", message.getSenderName());
        map.put("senderEmail", message.getSenderEmail());
        map.put("content", message.getContent());
        map.put("createdAt", message.getCreatedAt());
        map.put("aiGenerated", message.isAiGenerated());

        return map;
    }
}