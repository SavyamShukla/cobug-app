package com.cobug.reviews;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="review_files", indexes={@Index(name="idx_review_file_review", columnList="review_id")})
public class ReviewFile {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="review_id", nullable=false) private UUID reviewId;
    @Column(nullable=false, length=2000) private String path;
    @Column(name="change_type", nullable=false, length=20) private String changeType;
    @Column(name="base_sha", length=100) private String baseSha;
    @Column(name="content_sha", length=100) private String contentSha;
    @Column(nullable=false) private long additions;
    @Column(nullable=false) private long deletions;
    @Column(name="size_bytes", nullable=false) private long sizeBytes;
    @Column(name="before_content", columnDefinition="TEXT") private String beforeContent;
    @Column(name="after_content", columnDefinition="TEXT") private String afterContent;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @PrePersist void create(){if(createdAt==null)createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getReviewId(){return reviewId;} public void setReviewId(UUID v){reviewId=v;} public String getPath(){return path;} public void setPath(String v){path=v;}
    public String getChangeType(){return changeType;} public void setChangeType(String v){changeType=v;} public String getBaseSha(){return baseSha;} public void setBaseSha(String v){baseSha=v;} public String getContentSha(){return contentSha;} public void setContentSha(String v){contentSha=v;}
    public long getAdditions(){return additions;} public void setAdditions(long v){additions=v;} public long getDeletions(){return deletions;} public void setDeletions(long v){deletions=v;} public long getSizeBytes(){return sizeBytes;} public void setSizeBytes(long v){sizeBytes=v;} public String getBeforeContent(){return beforeContent;} public void setBeforeContent(String v){beforeContent=v;} public String getAfterContent(){return afterContent;} public void setAfterContent(String v){afterContent=v;} public Instant getCreatedAt(){return createdAt;}
}
