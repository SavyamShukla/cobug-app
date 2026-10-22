package com.cobug.chat;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_chat_messages", indexes = {
        @Index(name = "idx_chat_project_created", columnList = "project_id,created_at")
})
public class ProjectChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "sender_id", nullable = false) private UUID senderId;
    @Column(name = "sender_name", nullable = false, length = 160) private String senderName;
    @Column(name = "sender_email", nullable = false, length = 255) private String senderEmail;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "ai_generated", nullable = false) private boolean aiGenerated = false;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId(){return id;} public UUID getProjectId(){return projectId;} public void setProjectId(UUID v){projectId=v;}
    public UUID getSenderId(){return senderId;} public void setSenderId(UUID v){senderId=v;} public String getSenderName(){return senderName;} public void setSenderName(String v){senderName=v;}
    public String getSenderEmail(){return senderEmail;} public void setSenderEmail(String v){senderEmail=v;} public String getContent(){return content;} public void setContent(String v){content=v;} public boolean isAiGenerated(){return aiGenerated;} public void setAiGenerated(boolean v){aiGenerated=v;} public Instant getCreatedAt(){return createdAt;}
}
