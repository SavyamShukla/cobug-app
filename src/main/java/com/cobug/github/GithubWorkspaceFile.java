package com.cobug.github;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspace_github_files", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_github_file", columnNames = {"project_id","user_id","path"}))
public class GithubWorkspaceFile {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional=false) @JoinColumn(name="project_id",nullable=false) private Project project;
    @ManyToOne(fetch = FetchType.LAZY, optional=false) @JoinColumn(name="user_id",nullable=false) private User user;
    @Column(nullable=false,length=500) private String repositoryFullName;
    @Column(nullable=false,length=255) private String branch;
    @Column(nullable=false,length=2000) private String path;
    @Column(length=100) private String commitSha;
    @Column(length=100) private String blobSha;
    @Column(name="base_main_version_id") private UUID baseMainVersionId;
    @Column(name="base_main_version_number") private Long baseMainVersionNumber;
    @Column(nullable=false) private LocalDateTime addedAt;
    @PrePersist protected void onCreate(){addedAt=LocalDateTime.now();}
    public UUID getId(){return id;} public Project getProject(){return project;} public void setProject(Project v){project=v;} public User getUser(){return user;} public void setUser(User v){user=v;}
    public String getRepositoryFullName(){return repositoryFullName;} public void setRepositoryFullName(String v){repositoryFullName=v;} public String getBranch(){return branch;} public void setBranch(String v){branch=v;} public String getPath(){return path;} public void setPath(String v){path=v;} public String getCommitSha(){return commitSha;} public void setCommitSha(String v){commitSha=v;} public String getBlobSha(){return blobSha;} public void setBlobSha(String v){blobSha=v;} public UUID getBaseMainVersionId(){return baseMainVersionId;} public void setBaseMainVersionId(UUID v){baseMainVersionId=v;} public Long getBaseMainVersionNumber(){return baseMainVersionNumber;} public void setBaseMainVersionNumber(Long v){baseMainVersionNumber=v;} public LocalDateTime getAddedAt(){return addedAt;}
}
