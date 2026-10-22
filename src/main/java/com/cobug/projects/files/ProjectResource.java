package com.cobug.projects.files;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="project_resources", indexes={@Index(name="idx_resource_project_created",columnList="project_id,created_at")})
public class ProjectResource {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="project_id",nullable=false) private UUID projectId;
    @Column(name="uploaded_by",nullable=false) private UUID uploadedBy;
    @Column(name="original_name",nullable=false,length=255) private String originalName;
    @Column(name="storage_key",nullable=false,length=500,unique=true) private String storageKey;
    @Column(name="content_type",length=180) private String contentType;
    @Column(nullable=false) private long size;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @PrePersist void onCreate(){if(createdAt==null)createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getProjectId(){return projectId;} public void setProjectId(UUID v){projectId=v;} public UUID getUploadedBy(){return uploadedBy;} public void setUploadedBy(UUID v){uploadedBy=v;}
    public String getOriginalName(){return originalName;} public void setOriginalName(String v){originalName=v;} public String getStorageKey(){return storageKey;} public void setStorageKey(String v){storageKey=v;} public String getContentType(){return contentType;} public void setContentType(String v){contentType=v;} public long getSize(){return size;} public void setSize(long v){size=v;} public Instant getCreatedAt(){return createdAt;}
}
