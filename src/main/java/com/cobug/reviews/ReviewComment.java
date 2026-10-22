package com.cobug.reviews;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="review_comments", indexes={@Index(name="idx_review_comment_review", columnList="review_id,created_at")})
public class ReviewComment {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="review_id", nullable=false) private UUID reviewId;
    @Column(name="author_id", nullable=false) private UUID authorId;
    @Column(name="author_name", nullable=false, length=160) private String authorName;
    @Column(name="file_path", length=2000) private String filePath;
    @Column(columnDefinition="TEXT", nullable=false) private String body;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @PrePersist void create(){if(createdAt==null)createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getReviewId(){return reviewId;} public void setReviewId(UUID v){reviewId=v;} public UUID getAuthorId(){return authorId;} public void setAuthorId(UUID v){authorId=v;} public String getAuthorName(){return authorName;} public void setAuthorName(String v){authorName=v;} public String getFilePath(){return filePath;} public void setFilePath(String v){filePath=v;} public String getBody(){return body;} public void setBody(String v){body=v;} public Instant getCreatedAt(){return createdAt;}
}
