package com.cobug.github;

import com.cobug.auth.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "github_connections", uniqueConstraints = @UniqueConstraint(name = "uk_github_connection_user", columnNames = "user_id"))
public class GithubConnection {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
    @Column(nullable = false)
    private Long installationId;
    @Column(nullable = false, length = 200)
    private String githubLogin;
    @Column(nullable = false)
    private Long githubAccountId;
    @Column(nullable = false)
    private LocalDateTime connectedAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { connectedAt = LocalDateTime.now(); updatedAt = connectedAt; }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
    public UUID getId(){return id;}
    public User getUser(){return user;} public void setUser(User v){user=v;}
    public Long getInstallationId(){return installationId;} public void setInstallationId(Long v){installationId=v;}
    public String getGithubLogin(){return githubLogin;} public void setGithubLogin(String v){githubLogin=v;}
    public Long getGithubAccountId(){return githubAccountId;} public void setGithubAccountId(Long v){githubAccountId=v;}
    public LocalDateTime getConnectedAt(){return connectedAt;} public LocalDateTime getUpdatedAt(){return updatedAt;}
}
