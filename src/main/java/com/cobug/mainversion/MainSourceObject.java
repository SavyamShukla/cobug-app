package com.cobug.mainversion;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Content-addressed Main artifact metadata.
 * Small text source is kept in PostgreSQL; large/binary project artifacts are
 * kept in object storage and referenced by storageKey.
 */
@Entity
@Table(name = "main_source_objects", uniqueConstraints = @UniqueConstraint(name = "uk_main_source_sha", columnNames = "sha256"))
public class MainSourceObject {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 128) private String sha256;
    @Column(columnDefinition = "TEXT", nullable = true) private String content;
    @Column(nullable = false) private long sizeBytes;
    @Column(length = 20) private String storageType = "DATABASE";
    @Column(length = 700) private String storageKey;
    @Column(length = 180) private String contentType;
    @Column(length = 100) private String gitBlobSha;
    @Column private Boolean binaryArtifact = false;

    public MainSourceObject() {}

    /**
     * The database historically defined main_source_objects.content as NOT NULL.
     * Large/binary artifacts intentionally keep their bytes outside PostgreSQL,
     * so persist an empty marker for those rows instead of null. The API never
     * treats this marker as file content when storageType=LOCAL_STORAGE.
     */
    @PrePersist
    @PreUpdate
    protected void normalizeExternalContent() {
        if ("LOCAL_STORAGE".equalsIgnoreCase(storageType) && content == null) {
            content = "";
        }
    }
    public UUID getId(){return id;}
    public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
    public String getContent(){return content;} public void setContent(String v){content=v;}
    public long getSizeBytes(){return sizeBytes;} public void setSizeBytes(long v){sizeBytes=v;}
    public String getStorageType(){return storageType == null || storageType.isBlank() ? "DATABASE" : storageType;} public void setStorageType(String v){storageType=v;}
    public String getStorageKey(){return storageKey;} public void setStorageKey(String v){storageKey=v;}
    public String getContentType(){return contentType;} public void setContentType(String v){contentType=v;}
    public String getGitBlobSha(){return gitBlobSha;} public void setGitBlobSha(String v){gitBlobSha=v;}
    public boolean isBinaryArtifact(){return Boolean.TRUE.equals(binaryArtifact);} public void setBinaryArtifact(boolean v){binaryArtifact=v;}
}
