package com.cobug.mainversion;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "main_versions", uniqueConstraints = @UniqueConstraint(name = "uk_main_version_project_number", columnNames = {"project_id", "version_number"}))
public class MainVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false)
    private Project project;
    @Column(name = "version_number", nullable = false) private long versionNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(length = 500) private String message;
    @Column(name = "source_root_hash", nullable = false, length = 128) private String sourceRootHash;
    @Column(name = "source_commit_sha", length = 100) private String sourceCommitSha;
    @Column(name = "pushed_commit_sha", length = 100) private String pushedCommitSha;
    @Column(name = "pushed_at") private LocalDateTime pushedAt;
    public MainVersion() {}
    @PrePersist void onCreate(){ if(createdAt==null) createdAt=LocalDateTime.now(); }
    public UUID getId(){return id;} public Project getProject(){return project;} public void setProject(Project v){project=v;}
    public long getVersionNumber(){return versionNumber;} public void setVersionNumber(long v){versionNumber=v;}
    public User getCreatedBy(){return createdBy;} public void setCreatedBy(User v){createdBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public String getMessage(){return message;} public void setMessage(String v){message=v;}
    public String getSourceRootHash(){return sourceRootHash;} public void setSourceRootHash(String v){sourceRootHash=v;}
    public String getSourceCommitSha(){return sourceCommitSha;} public void setSourceCommitSha(String v){sourceCommitSha=v;}
    public String getPushedCommitSha(){return pushedCommitSha;} public void setPushedCommitSha(String v){pushedCommitSha=v;}
    public LocalDateTime getPushedAt(){return pushedAt;} public void setPushedAt(LocalDateTime v){pushedAt=v;}
}
