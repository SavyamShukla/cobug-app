package com.cobug.source;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_source_workspaces",
        uniqueConstraints = @UniqueConstraint(name = "uk_source_workspace_project_user", columnNames = {"project_id", "user_id"}))
public class ProjectSourceWorkspace {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "local_path", length = 2000)
    private String localPath;

    @Column(name = "base_main_version_id")
    private UUID baseMainVersionId;

    @Column(name = "base_main_version_number")
    private Long baseMainVersionNumber;

    @Column(name = "base_commit_sha", length = 100)
    private String baseCommitSha;

    @Column(name = "source_root_hash", length = 128)
    private String sourceRootHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void create() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void update() { updatedAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public Project getProject() { return project; }
    public void setProject(Project value) { project = value; }
    public User getUser() { return user; }
    public void setUser(User value) { user = value; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String value) { localPath = value; }
    public UUID getBaseMainVersionId() { return baseMainVersionId; }
    public void setBaseMainVersionId(UUID value) { baseMainVersionId = value; }
    public Long getBaseMainVersionNumber() { return baseMainVersionNumber; }
    public void setBaseMainVersionNumber(Long value) { baseMainVersionNumber = value; }
    public String getBaseCommitSha() { return baseCommitSha; }
    public void setBaseCommitSha(String value) { baseCommitSha = value; }
    public String getSourceRootHash() { return sourceRootHash; }
    public void setSourceRootHash(String value) { sourceRootHash = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
