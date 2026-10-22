package com.cobug.tasks;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "project_tasks", indexes = {
        @Index(name = "idx_task_project_created", columnList = "project_id,created_at"),
        @Index(name = "idx_task_project_status", columnList = "project_id,status"),
        @Index(name = "idx_task_project_visibility", columnList = "project_id,visibility"),
        @Index(name = "idx_task_project_assignee", columnList = "project_id,assignee_id")
})
public class ProjectTask {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name="project_id", nullable=false) private UUID projectId;
    @Column(nullable=false,length=240) private String title;
    @Column(columnDefinition="TEXT") private String description;
    @Column(nullable=false,length=30) private String status = "TODO";
    @Column(nullable=false,length=30) private String priority = "MEDIUM";
    @Column(nullable=false,length=20) private String visibility = "PUBLIC";
    @Column(name="assignee_id") private UUID assigneeId;
    @Column(name="assignee_name",length=160) private String assigneeName;
    @Column(name="created_by",nullable=false) private UUID createdBy;
    @Column(name="due_date") private LocalDate dueDate;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    @PrePersist
    void create(){
        Instant now=Instant.now();
        if(createdAt==null)createdAt=now;
        if(updatedAt==null)updatedAt=now;
        if(status==null||status.isBlank())status="TODO";
        if(priority==null||priority.isBlank())priority="MEDIUM";
        if(visibility==null||visibility.isBlank())visibility="PUBLIC";
    }

    @PreUpdate
    void update(){updatedAt=Instant.now();}

    public UUID getId(){return id;}
    public UUID getProjectId(){return projectId;} public void setProjectId(UUID v){projectId=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getPriority(){return priority;} public void setPriority(String v){priority=v;}
    public String getVisibility(){return visibility;} public void setVisibility(String v){visibility=v;}
    public UUID getAssigneeId(){return assigneeId;} public void setAssigneeId(UUID v){assigneeId=v;}
    public String getAssigneeName(){return assigneeName;} public void setAssigneeName(String v){assigneeName=v;}
    public UUID getCreatedBy(){return createdBy;} public void setCreatedBy(UUID v){createdBy=v;}
    public LocalDate getDueDate(){return dueDate;} public void setDueDate(LocalDate v){dueDate=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
