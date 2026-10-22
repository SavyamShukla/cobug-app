package com.cobug.tasks;

import com.cobug.activity.ProjectActivityService;
import com.cobug.auth.User;
import com.cobug.projects.ProjectAccessService;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
public class ProjectTaskController {
    private final ProjectTaskRepository repo;
    private final ProjectAccessService access;
    private final ProjectActivityService activity;
    private final ProjectMemberRepository members;

    public ProjectTaskController(ProjectTaskRepository repo, ProjectAccessService access, ProjectActivityService activity, ProjectMemberRepository members){
        this.repo=repo; this.access=access; this.activity=activity; this.members=members;
    }

    public record TaskRequest(String title,String description,String status,String priority,String visibility,UUID assigneeId,LocalDate dueDate){}

    @GetMapping
    public List<Map<String,Object>> list(@PathVariable UUID projectId, Authentication a){
        User user=access.requireMember(projectId,a.getName());
        LinkedHashMap<UUID,ProjectTask> visible=new LinkedHashMap<>();

        Stream.concat(
                repo.findByProjectIdAndVisibilityOrderByCreatedAtDesc(projectId,"PUBLIC").stream(),
                repo.findByProjectIdAndCreatedByOrderByCreatedAtDesc(projectId,user.getId()).stream()
        ).forEach(t->visible.putIfAbsent(t.getId(),t));

        UUID ownerId = access.getProject(projectId).getOwner().getId();
        return visible.values().stream().map(t -> map(t, user, ownerId)).toList();
    }

    @PostMapping
    public Map<String,Object> create(@PathVariable UUID projectId,@RequestBody TaskRequest r,Authentication a){
        User u=access.requireMember(projectId,a.getName());
        if(r==null||r.title()==null||r.title().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Task title is required.");

        Project project=access.getProject(projectId);
        boolean owner=project.getOwner().getId().equals(u.getId());
        UUID assignee=r.assigneeId();

        // Regular members create work for themselves. They cannot assign work
        // to another member through the API.
        if(!owner){
            if(assignee!=null && !assignee.equals(u.getId()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Members can only assign a task to themselves.");
            assignee=u.getId();
        } else if(assignee!=null){
            // Owner may assign to any member, including themselves.
            access.requireMember(projectId, a.getName());
            if(!assignee.equals(u.getId())){
                // Membership is checked by the repository-backed access helper below.
                // A target that is not a member must be rejected.
                requireProjectMember(projectId, assignee);
            }
        }

        ProjectTask t=new ProjectTask();
        t.setProjectId(projectId);
        t.setTitle(r.title().trim());
        t.setDescription(r.description()==null?"":r.description().trim());
        t.setStatus(validStatus(r.status()));
        t.setPriority(validPriority(r.priority()));
        t.setCreatedBy(u.getId());
        t.setAssigneeId(assignee);
        t.setDueDate(r.dueDate());

        // Owner-assigned work is always public. Member-created work may be private.
        boolean assignedByOwner=owner && assignee!=null;
        t.setVisibility(assignedByOwner ? "PUBLIC" : validVisibility(r.visibility()));

        ProjectTask saved=repo.save(t);
        activity.record(projectId,u,"CREATED_TASK","TASK",saved.getId().toString(),"Created task: "+saved.getTitle());
        return map(saved, u, project.getOwner().getId());
    }

    @PatchMapping("/{taskId}")
    public Map<String,Object> update(@PathVariable UUID projectId,@PathVariable UUID taskId,@RequestBody TaskRequest r,Authentication a){
        User u=access.requireMember(projectId,a.getName());
        if(r==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Task update payload is required.");
        Project project=access.getProject(projectId);
        boolean owner=project.getOwner().getId().equals(u.getId());
        ProjectTask t=repo.findByIdAndProjectId(taskId,projectId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Task not found."));

        boolean assignee=u.getId().equals(t.getAssigneeId());
        boolean creator=u.getId().equals(t.getCreatedBy());

        // Status is controlled by the member responsible for the task.
        if(r!=null && r.status()!=null){
            if(!assignee && !(owner && t.getAssigneeId()==null))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned member can change task status.");
            t.setStatus(validStatus(r.status()));
        }

        // Creator/owner controls task details. The assignee can always update status.
        if(r!=null && (creator || owner)){
            if(r.title()!=null&&!r.title().isBlank())t.setTitle(r.title().trim());
            if(r.description()!=null)t.setDescription(r.description().trim());
            if(r.priority()!=null)t.setPriority(validPriority(r.priority()));
            if(r.dueDate()!=null)t.setDueDate(r.dueDate());

            if(r.assigneeId()!=null && owner){
                requireProjectMember(projectId,r.assigneeId());
                t.setAssigneeId(r.assigneeId());
                // Anything explicitly assigned by owner is public.
                t.setVisibility("PUBLIC");
            }

            if(r.visibility()!=null && !owner){
                // A member may change visibility only for their own task.
                if(!creator) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can only change visibility of your own task.");
                t.setVisibility(validVisibility(r.visibility()));
            } else if(r.visibility()!=null && owner && t.getAssigneeId()==null){
                t.setVisibility(validVisibility(r.visibility()));
            }
        } else if(r!=null && (r.title()!=null || r.description()!=null || r.priority()!=null || r.assigneeId()!=null || r.visibility()!=null || r.dueDate()!=null)){
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the task creator or project owner can edit task details.");
        }

        // A task assigned to another member by the owner can never become private.
        if(owner && t.getAssigneeId()!=null){
            t.setVisibility("PUBLIC");
        }

        ProjectTask saved=repo.save(t);
        activity.record(projectId,u,"UPDATED_TASK","TASK",saved.getId().toString(),"Updated task: "+saved.getTitle());
        return map(saved, u, project.getOwner().getId());
    }

    @DeleteMapping("/{taskId}")
    public void delete(@PathVariable UUID projectId,@PathVariable UUID taskId,Authentication a){
        User u=access.requireMember(projectId,a.getName());
        Project project=access.getProject(projectId);
        ProjectTask t=repo.findByIdAndProjectId(taskId,projectId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Task not found."));
        if(!u.getId().equals(t.getCreatedBy()) && !u.getId().equals(project.getOwner().getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the task creator or project owner can delete this task.");
        repo.delete(t);
        activity.record(projectId,u,"DELETED_TASK","TASK",taskId.toString(),"Deleted task: "+t.getTitle());
    }

    private void requireProjectMember(UUID projectId, UUID userId){
        // ProjectAccessService exposes membership by email, so use the project
        // member repository through a small local lookup service would be cleaner;
        // this controller instead verifies by loading the project and its members
        // through a repository injected below.
        if(!members.existsByProjectIdAndUserId(projectId,userId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Assignee must be a member of this project.");
    }


    private String validStatus(String s){String v=s==null?"TODO":s.toUpperCase(Locale.ROOT);return Set.of("TODO","IN_PROGRESS","BLOCKED","DONE").contains(v)?v:"TODO";}
    private String validPriority(String s){String v=s==null?"MEDIUM":s.toUpperCase(Locale.ROOT);return Set.of("LOW","MEDIUM","HIGH","URGENT").contains(v)?v:"MEDIUM";}
    private String validVisibility(String s){String v=s==null?"PRIVATE":s.toUpperCase(Locale.ROOT);return Set.of("PUBLIC","PRIVATE").contains(v)?v:"PRIVATE";}

    private Map<String,Object> map(ProjectTask t, User viewer, UUID ownerId){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",t.getId());m.put("title",t.getTitle());m.put("description",t.getDescription());m.put("status",t.getStatus());
        m.put("priority",t.getPriority());m.put("visibility",t.getVisibility());m.put("assigneeId",t.getAssigneeId());
        m.put("assigneeName",t.getAssigneeName());m.put("createdBy",t.getCreatedBy());m.put("dueDate",t.getDueDate());
        m.put("createdAt",t.getCreatedAt());m.put("updatedAt",t.getUpdatedAt());
        // This is calculated from the authenticated backend user, not from localStorage.
        // The frontend can therefore render the correct status control even when an old
        // cached user object contains a stale/missing id.
        boolean canUpdateStatus = viewer != null &&
                (viewer.getId().equals(t.getAssigneeId()) ||
                 (viewer.getId().equals(ownerId) && t.getAssigneeId() == null));
        m.put("canUpdateStatus", canUpdateStatus);
        return m;
    }
}
