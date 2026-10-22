package com.cobug.ai;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.activity.ProjectActivity;
import com.cobug.activity.ProjectActivityRepository;
import com.cobug.chat.ProjectChatMessage;
import com.cobug.chat.ProjectChatMessageRepository;
import com.cobug.github.GithubDtos;
import com.cobug.github.GithubService;
import com.cobug.projects.*;
import com.cobug.projects.files.ProjectResource;
import com.cobug.projects.files.ProjectResourceRepository;
import com.cobug.storage.ProjectStorageService;
import com.cobug.tasks.ProjectTask;
import com.cobug.tasks.ProjectTaskRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectAiContextService {
    private final ProjectAccessService access; private final ProjectMemberRepository members; private final UserRepository users;
    private final ProjectResourceRepository resources; private final ProjectActivityRepository activity; private final ProjectChatMessageRepository chat;
    private final ProjectTaskRepository tasks; private final GithubService github; private final ProjectStorageService storage;
    public ProjectAiContextService(ProjectAccessService access,ProjectMemberRepository members,UserRepository users,ProjectResourceRepository resources,ProjectActivityRepository activity,ProjectChatMessageRepository chat,ProjectTaskRepository tasks,GithubService github,ProjectStorageService storage){
        this.access=access;this.members=members;this.users=users;this.resources=resources;this.activity=activity;this.chat=chat;this.tasks=tasks;this.github=github;this.storage=storage;
    }
    public String build(UUID projectId,String email,String question){
        User user=access.requireMember(projectId,email); Project p=access.getProject(projectId); StringBuilder s=new StringBuilder();
        s.append("PROJECT\nName: ").append(p.getName()).append("\nStatus: ").append(p.getStatus()).append("\nDescription: ").append(nvl(p.getDescription())).append("\n");
        s.append("Project Aim JSON: ").append(truncate(nvl(p.getAimDetails()),12000)).append("\n\n");
        s.append("MEMBERS\n");
        List<ProjectMember> pm=members.findByProjectIdOrderByJoinedAtAsc(projectId); for(ProjectMember m:pm){String name=users.findById(m.getUserId()).map(User::getName).orElse("Unknown");s.append("- ").append(name).append(" (role ").append(m.getRole()).append(")\n");}
        s.append("- Owner: ").append(p.getOwner().getName()).append("\n\n");
        s.append("TASKS\n"); List<ProjectTask> ts=visibleTasks(projectId,user.getId()); if(ts.isEmpty())s.append("No tasks recorded.\n"); else for(ProjectTask t:ts)s.append("- [").append(t.getStatus()).append("] ").append(t.getTitle()).append(" | priority=").append(t.getPriority()).append(" | due=").append(t.getDueDate()).append(" | assignee=").append(nvl(t.getAssigneeName())).append("\n");
        s.append("\nSHARED FILES (metadata only)\n"); List<ProjectResource> rs=resources.findByProjectIdOrderByCreatedAtDesc(projectId); if(rs.isEmpty())s.append("No shared files.\n"); else rs.stream().limit(60).forEach(r->s.append("- ").append(r.getOriginalName()).append(" | ").append(r.getContentType()).append(" | ").append(r.getSize()).append(" bytes\n"));
        s.append("\nACTIVITY (recent)\n"); List<ProjectActivity> acts=activity.findTop200ByProjectIdOrderByCreatedAtDesc(projectId); acts.stream().limit(25).forEach(a->s.append("- ").append(a.getCreatedAt()).append(" | ").append(a.getActorName()).append(" | ").append(a.getAction()).append(" | ").append(nvl(a.getDetails())).append("\n"));
        s.append("\nRECENT CHAT\n"); List<ProjectChatMessage> msgs=new ArrayList<>(chat.findTop100ByProjectIdOrderByCreatedAtDesc(projectId)); Collections.reverse(msgs); msgs.stream().skip(Math.max(0,msgs.size()-40)).forEach(m->s.append("- ").append(m.getSenderName()).append(": ").append(truncate(m.getContent(),1200)).append("\n"));
        s.append("\nGITHUB MAIN\n"); try { GithubDtos.RepositoryResponse repo=github.getRepository(projectId,email); if(repo==null)s.append("No GitHub repository connected.\n"); else {s.append("Repository: ").append(repo.fullName()).append(" branch=").append(repo.branch()).append("\n"); if(needsSourceContext(question)){ GithubDtos.TreeResponse tree=github.tree(projectId,email,"",repo.branch()); tree.files().stream().limit(250).forEach(f->s.append("- ").append(f.path()).append(" [").append(f.language()).append("]\n")); if(isCodeOverviewQuestion(question)) appendCodeOverviewGithubFiles(s,projectId,email,repo,tree); else appendRelevantGithubFiles(s,projectId,email,repo,tree,question); } else s.append("Source tree omitted for this general question.\n"); }} catch(Exception e){s.append("GitHub context unavailable: ").append(e.getMessage()).append("\n");}
        s.append("\nCURRENT USER WORKSPACE\n"); try {List<com.cobug.storage.StoredFileInfo> wf=storage.listWorkspaceFiles(p,user); if(wf.isEmpty())s.append("No workspace files.\n"); else {wf.stream().limit(120).forEach(f->s.append("- ").append(f.path()).append(" [").append(f.language()).append("]\n")); if(isCodeOverviewQuestion(question)) appendCodeOverviewWorkspaceFiles(s,p,user,wf); else appendWorkspaceSnippets(s,p,user,wf,question);}} catch(Exception e){s.append("Workspace context unavailable.\n");}
        return s.toString();
    }
    private List<ProjectTask> visibleTasks(UUID projectId, UUID userId){
        LinkedHashMap<UUID, ProjectTask> visible=new LinkedHashMap<>();
        tasks.findTop50ByProjectIdAndVisibilityOrderByUpdatedAtDesc(projectId,"PUBLIC").forEach(t->visible.put(t.getId(),t));
        tasks.findTop50ByProjectIdAndCreatedByOrderByUpdatedAtDesc(projectId,userId).forEach(t->visible.put(t.getId(),t));
        tasks.findTop50ByProjectIdAndAssigneeIdOrderByUpdatedAtDesc(projectId,userId).forEach(t->visible.put(t.getId(),t));
        return visible.values().stream().sorted(Comparator.comparing(ProjectTask::getUpdatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).limit(50).toList();
    }
    private void appendRelevantGithubFiles(StringBuilder s,UUID id,String email,GithubDtos.RepositoryResponse repo,GithubDtos.TreeResponse tree,String q){
        String lower=nvl(q).toLowerCase(Locale.ROOT); List<GithubDtos.TreeNode> candidates=tree.files().stream().filter(f->isRelevant(f.path(),lower)).limit(5).toList(); for(var f:candidates){try{var x=github.file(id,email,f.path(),repo.branch()); if(!x.binary())s.append("\nSOURCE FILE ").append(f.path()).append("\n").append(truncate(x.content(),7000)).append("\n");}catch(Exception ignored){}}
    }
    private void appendCodeOverviewGithubFiles(StringBuilder s,UUID id,String email,GithubDtos.RepositoryResponse repo,GithubDtos.TreeResponse tree){
        s.append("\nCODE OVERVIEW SOURCE FILES\n");
        tree.files().stream().filter(f->isUsefulSourceFile(f.path())).sorted(Comparator.comparingInt((GithubDtos.TreeNode f)->sourceScore(f.path())).reversed().thenComparing(GithubDtos.TreeNode::path)).limit(6).forEach(f->{try{var x=github.file(id,email,f.path(),repo.branch()); if(!x.binary())s.append("\nSOURCE FILE ").append(f.path()).append("\n").append(truncate(x.content(),5000)).append("\n");}catch(Exception ignored){}});
    }
    private void appendWorkspaceSnippets(StringBuilder s,Project p,User u,List<com.cobug.storage.StoredFileInfo> files,String q){String lower=nvl(q).toLowerCase(Locale.ROOT);int count=0;for(var f:files){if(count>=5||!isRelevant(f.path(),lower)||f.size()>12000)continue;try{byte[] b=storage.readWorkspaceFile(p,u,f.path());String text=new String(b,StandardCharsets.UTF_8);if(text.indexOf('\u0000')>=0)continue;s.append("\nWORKSPACE FILE ").append(f.path()).append("\n").append(truncate(text,6000)).append("\n");count++;}catch(Exception ignored){}}}
    private void appendCodeOverviewWorkspaceFiles(StringBuilder s,Project p,User u,List<com.cobug.storage.StoredFileInfo> files){
        s.append("\nCODE OVERVIEW WORKSPACE FILES\n");
        files.stream().filter(f->isUsefulSourceFile(f.path())).sorted(Comparator.comparingInt((com.cobug.storage.StoredFileInfo f)->sourceScore(f.path())).reversed().thenComparing(com.cobug.storage.StoredFileInfo::path)).limit(6).forEach(f->{try{byte[] b=storage.readWorkspaceFile(p,u,f.path());String text=new String(b,StandardCharsets.UTF_8);if(text.indexOf('\u0000')>=0)return;s.append("\nWORKSPACE FILE ").append(f.path()).append("\n").append(truncate(text,5000)).append("\n");}catch(Exception ignored){}});
    }
    private boolean needsSourceContext(String q){String x=nvl(q).toLowerCase(Locale.ROOT);return x.contains("code")||x.contains("file")||x.contains("github")||x.contains("main")||x.contains("repository")||x.contains("bug")||x.contains("error")||x.contains("workspace")||x.contains("implement")||x.contains("function")||x.contains("class")||x.contains("architecture")||x.contains("project doing")||x.contains("project do");}
    private boolean isCodeOverviewQuestion(String q){String x=nvl(q).toLowerCase(Locale.ROOT);return x.contains("what is the code")||x.contains("what does the code")||x.contains("what is code")||x.contains("what does this project")||x.contains("what is this project")||x.contains("project doing")||x.contains("explain the project")||x.contains("explain this project")||x.contains("codebase")||x.contains("architecture");}
    private boolean isUsefulSourceFile(String path){String p=nvl(path).toLowerCase(Locale.ROOT);if(p.contains("node_modules/")||p.contains("/target/")||p.startsWith("target/")||p.contains("/build/")||p.contains("/.git/")||p.endsWith("package-lock.json")||p.endsWith("yarn.lock")||p.endsWith("mvnw")||p.endsWith("mvnw.cmd"))return false;return p.endsWith(".java")||p.endsWith(".kt")||p.endsWith(".py")||p.endsWith(".js")||p.endsWith(".jsx")||p.endsWith(".ts")||p.endsWith(".tsx")||p.endsWith(".vue")||p.endsWith(".css")||p.endsWith(".html")||p.endsWith(".xml")||p.endsWith(".json")||p.endsWith(".md");}
    private int sourceScore(String path){String p=nvl(path).toLowerCase(Locale.ROOT);int score=0;if(p.endsWith("readme.md"))score+=100;if(p.endsWith("pom.xml")||p.endsWith("package.json"))score+=90;if(p.contains("/src/main/java/"))score+=70;if(p.contains("/src/main/"))score+=60;if(p.contains("frontend/src/"))score+=55;if(p.endsWith("application.java")||p.endsWith("app.jsx")||p.endsWith("main.jsx"))score+=30;if(p.contains("controller")||p.contains("service")||p.contains("repository"))score+=20;return score;}
    private boolean isRelevant(String path,String q){String p=path.toLowerCase(Locale.ROOT);if(q.isBlank())return false;String[] parts=q.split("[^a-z0-9_./-]+");for(String x:parts)if(x.length()>=3&&(p.contains(x)||x.contains(p)))return true;return q.contains("code")||q.contains("file")||q.contains("bug")||q.contains("error");}
    private String nvl(String x){return x==null?"":x;} private String truncate(String x,int n){String v=nvl(x);return v.length()<=n?v:v.substring(0,n)+"…";}
}
