package com.cobug.github;

import com.cobug.projects.Project;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_github_repositories", uniqueConstraints = @UniqueConstraint(name = "uk_project_github_repo", columnNames = "project_id"))
public class GithubRepository {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "github_connection_id")
    private GithubConnection connection;

    /**
     * Project-level GitHub App installation. This is deliberately stored on
     * the project repository instead of relying on the currently logged-in
     * member's personal COBUG GitHub connection. That allows every project
     * member to browse the repository without connecting GitHub themselves.
     */
    @Column(name = "github_installation_id")
    private Long githubInstallationId;

    @Column(nullable = false, length = 200) private String owner;
    @Column(nullable = false, length = 200) private String repository;
    @Column(nullable = false, length = 255) private String branch;
    @Column(nullable = false, length = 500) private String fullName;
    @Column(nullable = false) private LocalDateTime connectedAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { connectedAt = LocalDateTime.now(); updatedAt = connectedAt; }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
    public UUID getId(){return id;} public Project getProject(){return project;} public void setProject(Project v){project=v;}
    public GithubConnection getConnection(){return connection;} public void setConnection(GithubConnection v){connection=v;}
    public Long getGithubInstallationId(){return githubInstallationId;} public void setGithubInstallationId(Long v){githubInstallationId=v;}
    public String getOwner(){return owner;} public void setOwner(String v){owner=v;} public String getRepository(){return repository;} public void setRepository(String v){repository=v;}
    public String getBranch(){return branch;} public void setBranch(String v){branch=v;} public String getFullName(){return fullName;} public void setFullName(String v){fullName=v;}
    public LocalDateTime getConnectedAt(){return connectedAt;} public LocalDateTime getUpdatedAt(){return updatedAt;}
}
