package com.cobug.ai;

import com.cobug.activity.ProjectActivity;
import com.cobug.activity.ProjectActivityRepository;
import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.projects.*;
import com.cobug.social.FriendRequestRepository;
import com.cobug.social.FriendRequestStatus;
import com.cobug.tasks.ProjectTask;
import com.cobug.tasks.ProjectTaskRepository;
import com.cobug.tasks.PersonalTask;
import com.cobug.tasks.PersonalTaskRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GlobalAiContextService {
    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectTaskRepository tasks;
    private final ProjectActivityRepository activity;
    private final ProjectInvitationRepository invitations;
    private final FriendRequestRepository friendRequests;
    private final PersonalTaskRepository personalTasks;

    public GlobalAiContextService(UserRepository users, ProjectRepository projects, ProjectMemberRepository members,
                                 ProjectTaskRepository tasks, ProjectActivityRepository activity,
                                 ProjectInvitationRepository invitations, FriendRequestRepository friendRequests,
                                 PersonalTaskRepository personalTasks) {
        this.users = users; this.projects = projects; this.members = members; this.tasks = tasks;
        this.activity = activity; this.invitations = invitations; this.friendRequests = friendRequests;
        this.personalTasks = personalTasks;
    }

    public String build(String email, String question) {
        User me = users.findByEmail(email).orElseThrow();
        StringBuilder s = new StringBuilder();
        s.append("USER PROFILE\n")
                .append("Name: ").append(me.getName()).append("\n")
                .append("Email: ").append(me.getEmail()).append("\n")
                .append("Role: ").append(me.getRole()).append("\n")
                .append("Joined: ").append(me.getCreatedAt()).append("\n\n");

        LinkedHashMap<UUID, Project> visible = new LinkedHashMap<>();
        projects.findByOwnerOrderByCreatedAtDesc(me).forEach(p -> visible.put(p.getId(), p));
        members.findByUserIdOrderByJoinedAtAsc(me.getId()).forEach(m -> projects.findById(m.getProjectId()).ifPresent(p -> visible.put(p.getId(), p)));

        s.append("PERSONAL TASKS\n");
        List<PersonalTask> ownTasks = personalTasks.findByUserIdOrderByCreatedAtDesc(me.getId());
        if (ownTasks.isEmpty()) s.append("No personal tasks yet.\n");
        else {
            long openPersonal = ownTasks.stream().filter(t -> !isPersonalDone(t)).count();
            long donePersonal = ownTasks.size() - openPersonal;
            s.append("Summary: ").append(openPersonal).append(" open, ").append(donePersonal).append(" completed\n");
            ownTasks.stream().limit(30).forEach(t -> s.append("- [").append(t.getStatus()).append("] ")
                    .append(t.getTitle()).append(" | priority=").append(t.getPriority())
                    .append(" | due=").append(t.getDueDate()).append("\n"));
        }

        s.append("\nPROJECTS\n");
        if (visible.isEmpty()) s.append("No projects yet.\n");
        int projectCount = 0;
        for (Project p : visible.values()) {
            if (projectCount++ >= 25) break;
            s.append("- ").append(p.getName()).append(" | status=").append(p.getStatus())
                    .append(" | description=").append(nvl(p.getDescription())).append("\n");
            s.append("  Project Aim: ").append(truncate(nvl(p.getAimDetails()), 3500)).append("\n");
            List<ProjectTask> ts = visibleTasks(p.getId(), me.getId());
            long open = ts.stream().filter(t -> !isDone(t)).count();
            long done = ts.size() - open;
            s.append("  Tasks: ").append(open).append(" open, ").append(done).append(" completed\n");
            ts.stream().limit(8).forEach(t -> s.append("    * [").append(t.getStatus()).append("] ").append(t.getTitle()).append(" | priority=").append(t.getPriority()).append(" | due=").append(t.getDueDate()).append("\n"));
            List<ProjectActivity> acts = activity.findTop200ByProjectIdOrderByCreatedAtDesc(p.getId());
            acts.stream().limit(5).forEach(a -> s.append("    activity: ").append(a.getCreatedAt()).append(" | ").append(a.getActorName()).append(" | ").append(a.getAction()).append(" | ").append(nvl(a.getDetails())).append("\n"));
        }

        s.append("\nPENDING PROJECT INVITATIONS\n");
        invitations.findByInvitedUserIdAndStatusOrderByCreatedAtDesc(me.getId(), InvitationStatus.PENDING).stream().limit(20).forEach(i -> {
            String projectName = projects.findById(i.getProjectId()).map(Project::getName).orElse("Unknown project");
            s.append("- ").append(projectName).append(" | role=").append(i.getRole()).append(" | invited=").append(i.getCreatedAt()).append("\n");
        });

        s.append("\nFRIENDS\n");
        friendRequests.findByRequesterIdOrRecipientIdOrderByUpdatedAtDesc(me.getId(), me.getId()).stream()
                .filter(r -> r.getStatus() == FriendRequestStatus.ACCEPTED).limit(30)
                .forEach(r -> {
                    UUID other = r.getRequesterId().equals(me.getId()) ? r.getRecipientId() : r.getRequesterId();
                    users.findById(other).ifPresent(u -> s.append("- ").append(u.getName()).append(" <").append(u.getEmail()).append(">\n"));
                });

        return s.toString();
    }

    private List<ProjectTask> visibleTasks(UUID projectId, UUID userId) {
        LinkedHashMap<UUID, ProjectTask> visible = new LinkedHashMap<>();
        tasks.findTop50ByProjectIdAndVisibilityOrderByUpdatedAtDesc(projectId, "PUBLIC").forEach(t -> visible.put(t.getId(), t));
        tasks.findTop50ByProjectIdAndCreatedByOrderByUpdatedAtDesc(projectId, userId).forEach(t -> visible.put(t.getId(), t));
        tasks.findTop50ByProjectIdAndAssigneeIdOrderByUpdatedAtDesc(projectId, userId).forEach(t -> visible.put(t.getId(), t));
        return visible.values().stream().sorted(Comparator.comparing(ProjectTask::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).limit(50).toList();
    }

    private boolean isPersonalDone(PersonalTask t) {
        String x = String.valueOf(t.getStatus()).toUpperCase(Locale.ROOT);
        return x.contains("DONE") || x.contains("COMPLETE") || x.contains("CLOSED") || x.contains("RESOLVED");
    }
    private boolean isDone(ProjectTask t) {
        String x = String.valueOf(t.getStatus()).toUpperCase(Locale.ROOT);
        return x.contains("DONE") || x.contains("COMPLETE") || x.contains("CLOSED") || x.contains("RESOLVED");
    }
    private String nvl(String s) { return s == null ? "" : s; }
    private String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0,n) + "…"; }
}
