package com.cobug.reviews;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_requests", indexes = {
        @Index(name = "idx_review_project_created", columnList = "project_id,created_at"),
        @Index(name = "idx_review_project_status", columnList = "project_id,status"),
        @Index(name = "idx_review_author", columnList = "author_id")
})
public class ReviewRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name="project_id", nullable=false) private UUID projectId;
    @Column(name="author_id", nullable=false) private UUID authorId;
    @Column(name="author_name", nullable=false, length=160) private String authorName;
    @Column(name="reviewer_id") private UUID reviewerId;
    @Column(name="reviewer_name", length=160) private String reviewerName;
    @Column(nullable=false, length=240) private String title;
    @Column(columnDefinition="TEXT") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private ReviewStatus status = ReviewStatus.SUBMITTED;
    @Enumerated(EnumType.STRING) @Column(name="validation_status", nullable=false, length=20) private ReviewValidationStatus validationStatus = ReviewValidationStatus.NOT_RUN;
    @Column(name="base_main_version_id") private UUID baseMainVersionId;
    @Column(name="base_main_version_number") private Long baseMainVersionNumber;
    @Column(name="base_commit_sha", length=100) private String baseCommitSha;
    @Column(name="merged_commit_sha", length=100) private String mergedCommitSha;
    @Column(name="merged_main_version_id") private UUID mergedMainVersionId;
    @Column(name="merged_main_version_number") private Long mergedMainVersionNumber;
    @Column(name="validation_main_version_id") private UUID validationMainVersionId;
    @Column(name="validation_main_version_number") private Long validationMainVersionNumber;
    @Column(name="total_points", nullable=false) private long totalPoints;
    @Column(name="approved_points", nullable=false) private long approvedPoints;
    @Column(name="files_changed", nullable=false) private int filesChanged;
    @Column(name="additions", nullable=false) private long additions;
    @Column(name="deletions", nullable=false) private long deletions;
    @Column(name="reviewer_comment", columnDefinition="TEXT") private String reviewerComment;
    @Column(name="work_number") private Integer workNumber;
    @Column(name="update_number") private Integer updateNumber = 0;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Column(name="merged_at") private Instant mergedAt;

    @PrePersist void create(){ Instant now=Instant.now(); if(createdAt==null) createdAt=now; if(updatedAt==null) updatedAt=now; }
    @PreUpdate void update(){ updatedAt=Instant.now(); }

    public UUID getId(){return id;} public UUID getProjectId(){return projectId;} public void setProjectId(UUID v){projectId=v;}
    public UUID getAuthorId(){return authorId;} public void setAuthorId(UUID v){authorId=v;} public String getAuthorName(){return authorName;} public void setAuthorName(String v){authorName=v;}
    public UUID getReviewerId(){return reviewerId;} public void setReviewerId(UUID v){reviewerId=v;} public String getReviewerName(){return reviewerName;} public void setReviewerName(String v){reviewerName=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public ReviewStatus getStatus(){return status;} public void setStatus(ReviewStatus v){status=v;} public ReviewValidationStatus getValidationStatus(){return validationStatus;} public void setValidationStatus(ReviewValidationStatus v){validationStatus=v;}
    public UUID getBaseMainVersionId(){return baseMainVersionId;} public void setBaseMainVersionId(UUID v){baseMainVersionId=v;} public Long getBaseMainVersionNumber(){return baseMainVersionNumber;} public void setBaseMainVersionNumber(Long v){baseMainVersionNumber=v;} public String getBaseCommitSha(){return baseCommitSha;} public void setBaseCommitSha(String v){baseCommitSha=v;} public String getMergedCommitSha(){return mergedCommitSha;} public void setMergedCommitSha(String v){mergedCommitSha=v;} public UUID getMergedMainVersionId(){return mergedMainVersionId;} public void setMergedMainVersionId(UUID v){mergedMainVersionId=v;} public Long getMergedMainVersionNumber(){return mergedMainVersionNumber;} public void setMergedMainVersionNumber(Long v){mergedMainVersionNumber=v;} public UUID getValidationMainVersionId(){return validationMainVersionId;} public void setValidationMainVersionId(UUID v){validationMainVersionId=v;} public Long getValidationMainVersionNumber(){return validationMainVersionNumber;} public void setValidationMainVersionNumber(Long v){validationMainVersionNumber=v;} public long getTotalPoints(){return totalPoints;} public void setTotalPoints(long v){totalPoints=v;}
    public long getApprovedPoints(){return approvedPoints;} public void setApprovedPoints(long v){approvedPoints=v;} public int getFilesChanged(){return filesChanged;} public void setFilesChanged(int v){filesChanged=v;}
    public long getAdditions(){return additions;} public void setAdditions(long v){additions=v;} public long getDeletions(){return deletions;} public void setDeletions(long v){deletions=v;}
    public String getReviewerComment(){return reviewerComment;} public void setReviewerComment(String v){reviewerComment=v;}
    public Integer getWorkNumber(){return workNumber;} public void setWorkNumber(Integer v){workNumber=v;}
    public Integer getUpdateNumber(){return updateNumber;} public void setUpdateNumber(Integer v){updateNumber=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public Instant getMergedAt(){return mergedAt;} public void setMergedAt(Instant v){mergedAt=v;}
}
