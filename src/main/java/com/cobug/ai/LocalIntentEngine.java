package com.cobug.ai;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.chat.ProjectChatMessage;
import com.cobug.chat.ProjectChatMessageRepository;
import com.cobug.activity.ProjectActivityRepository;
import com.cobug.github.*;
import com.cobug.main.MainFileResponse;
import com.cobug.main.MainResponse;
import com.cobug.main.MainService;
import com.cobug.mainversion.*;
import com.cobug.notifications.Notification;
import com.cobug.notifications.NotificationRepository;
import com.cobug.projects.*;
import com.cobug.projects.files.*;
import com.cobug.reviews.*;
import com.cobug.social.SocialService;
import com.cobug.source.ProjectSourceWorkspace;
import com.cobug.source.ProjectSourceWorkspaceRepository;
import com.cobug.tasks.*;
import com.cobug.workspace.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Account-scoped deterministic operators for the complete trained COBUG intent catalog.
 * Read intents query COBUG's existing services/repositories. This class never performs a
 * write/mutation on behalf of a natural-language request; WRITE_ACTION explains the UI path.
 *
 * REPLY FORMAT (rendered by AiMessageContent.jsx in the chat UI):
 *   Normal text            -> paragraphs, **bold**, `code`
 *   :::list Title ... :::  -> small scrollable list card.  Row = "Main text :: Small text :: Badge"
 *   :::facts Title ... ::: -> small key/value card.        Row = "Label :: Value"
 *   ```name ... ```        -> scrollable code card (file contents)
 * Technical IDs, tokens and commit hashes are never shown to the user.
 */
@Service
public class LocalIntentEngine {
    private static final String GENERIC_ERROR =
            "Hmm, I couldn't get that for you right now. 😅 Please try again in a moment, " +
            "or open that section in COBUG directly. Nothing was changed.";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Fields we prefer as the "main text" of a row, in order. */
    private static final List<String> TITLE_KEYS = List.of(
            "name", "title", "projectName", "fullName", "login", "githubLogin",
            "originalName", "path", "filename", "email", "action", "message", "content");
    /** Short fields we show as a small coloured badge. */
    private static final List<String> BADGE_KEYS = List.of("status", "role", "priority", "type", "state", "visibility");

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final PersonalTaskRepository personalTasks;
    private final ProjectTaskRepository projectTasks;
    private final NotificationRepository notifications;
    private final SocialService social;
    private final AiSettingsService aiSettings;
    private final GithubService github;
    private final GithubConnectionRepository githubConnections;
    private final GithubRepositoryRepository githubRepositories;
    private final GithubWorkspaceFileRepository githubWorkspaceFiles;
    private final MainService mainService;
    private final MainVersionService mainVersions;
    private final MainVersionRepository mainVersionRepository;
    private final MainVersionFileRepository mainVersionFiles;
    private final WorkspaceService workspaceService;
    private final ProjectAccessService projectAccess;
    private final ProjectFileRepository projectFiles;
    private final ProjectResourceRepository projectResources;
    private final RoadmapStationRepository roadmapStations;
    private final RoadmapPointRepository roadmapPoints;
    private final ProjectActivityRepository activities;
    private final ProjectChatMessageRepository projectChats;
    private final ProjectInvitationRepository invitations;
    private final ReviewService reviewService;
    private final ReviewRequestRepository reviews;
    private final ReviewFileRepository reviewFiles;
    private final ProjectSourceWorkspaceRepository sourceWorkspaces;
    private final ObjectMapper mapper;
    private final Map<String, BiFunction<IntentContext, String, String>> operators = new HashMap<>();

    public record IntentContext(String email, UUID projectId, String question) {}

    public LocalIntentEngine(UserRepository users, ProjectRepository projects,
            ProjectMemberRepository members, PersonalTaskRepository personalTasks,
            ProjectTaskRepository projectTasks, NotificationRepository notifications,
            SocialService social, AiSettingsService aiSettings, GithubService github,
            GithubConnectionRepository githubConnections, GithubRepositoryRepository githubRepositories,
            GithubWorkspaceFileRepository githubWorkspaceFiles, MainService mainService,
            MainVersionService mainVersions, MainVersionRepository mainVersionRepository,
            MainVersionFileRepository mainVersionFiles, WorkspaceService workspaceService,
            ProjectAccessService projectAccess, ProjectFileRepository projectFiles,
            ProjectResourceRepository projectResources, RoadmapStationRepository roadmapStations,
            RoadmapPointRepository roadmapPoints, ProjectActivityRepository activities,
            ProjectChatMessageRepository projectChats, ProjectInvitationRepository invitations,
            ReviewService reviewService, ReviewRequestRepository reviews, ReviewFileRepository reviewFiles,
            ProjectSourceWorkspaceRepository sourceWorkspaces, ObjectMapper mapper) {
        this.users=users; this.projects=projects; this.members=members; this.personalTasks=personalTasks;
        this.projectTasks=projectTasks; this.notifications=notifications; this.social=social;
        this.aiSettings=aiSettings; this.github=github; this.githubConnections=githubConnections;
        this.githubRepositories=githubRepositories; this.githubWorkspaceFiles=githubWorkspaceFiles;
        this.mainService=mainService; this.mainVersions=mainVersions; this.mainVersionRepository=mainVersionRepository;
        this.mainVersionFiles=mainVersionFiles; this.workspaceService=workspaceService; this.projectAccess=projectAccess;
        this.projectFiles=projectFiles; this.projectResources=projectResources; this.roadmapStations=roadmapStations;
        this.roadmapPoints=roadmapPoints; this.activities=activities; this.projectChats=projectChats;
        this.invitations=invitations; this.reviewService=reviewService; this.reviews=reviews;
        this.reviewFiles=reviewFiles; this.sourceWorkspaces=sourceWorkspaces; this.mapper=mapper;

        register("GREETINGS", this::greeting);
        register("GET_MY_PROFILE", this::profile);
        register("GET_MY_PROJECTS", this::myProjects);
        register("GET_MY_TASKS", this::myTasks);
        register("GET_AI_SETTINGS", this::getAiSettings);
        register("GET_BLOCKED_USERS", this::blockedUsers);
        register("GET_FRIENDS", this::friends);
        register("GET_FRIEND_MESSAGES", this::friendMessages);
        register("GET_GITHUB_CONNECTION", this::githubConnection);
        register("GET_GITHUB_FILE", this::githubFile);
        register("GET_GITHUB_REPOSITORIES", this::githubRepositories);
        register("GET_GITHUB_REPOSITORY", this::githubRepository);
        register("GET_GITHUB_TREE", this::githubTree);
        register("GET_GITHUB_WORKSPACE", this::githubWorkspace);
        register("GET_MAIN_FILE", this::mainFile);
        register("GET_MAIN_VERSION", this::mainVersion);
        register("GET_MAIN_VERSIONS", this::mainVersionList);
        register("GET_MAIN_VERSION_CONTENT", this::mainVersionContent);
        register("GET_NOTIFICATIONS", this::getNotifications);
        register("GET_PROJECT_ACTIVITY", this::projectActivity);
        register("GET_PROJECT_AIM", this::projectAim);
        register("GET_PROJECT_CHAT", this::projectChat);
        register("GET_PROJECT_CONTRIBUTIONS", this::projectContributions);
        register("GET_PROJECT_DETAILS", this::projectDetails);
        register("GET_PROJECT_FILE", this::projectFile);
        register("GET_PROJECT_FILE_TREE", this::projectFileTree);
        register("GET_PROJECT_INVITATIONS", this::projectInvitations);
        register("GET_PROJECT_MAIN", this::projectMain);
        register("GET_PROJECT_MEMBERS", this::projectMembers);
        register("GET_PROJECT_RESOURCE", this::projectResource);
        register("GET_PROJECT_RESOURCES", this::projectResources);
        register("GET_PROJECT_ROADMAP", this::projectRoadmap);
        register("GET_PROJECT_TASKS", this::projectTasks);
        register("GET_RECEIVED_FRIEND_REQUESTS", this::receivedFriendRequests);
        register("GET_RECEIVED_INVITATIONS", this::receivedInvitations);
        register("GET_REVIEWS", this::getReviews);
        register("GET_REVIEW_DETAILS", this::reviewDetails);
        register("GET_REVIEW_FILE", this::reviewFile);
        register("GET_REVIEW_TEST_PACKAGE", this::reviewTestPackage);
        register("GET_REVIEW_TEST_PACKAGE_DOWNLOAD", this::reviewTestPackageDownload);
        register("GET_SENT_FRIEND_REQUESTS", this::sentFriendRequests);
        register("GET_SENT_INVITATIONS", this::sentInvitations);
        register("GET_SHARED_PROJECTS_WITH_USER", this::sharedProjectsWithUser);
        register("GET_SOURCE_WORKSPACE", this::sourceWorkspace);
        register("GET_UNREAD_NOTIFICATION_COUNT", this::unreadNotificationCount);
        register("GET_WORKSPACE", this::workspace);
        register("GET_WORKSPACE_FILE", this::workspaceFile);
        register("SEARCH_USERS", this::searchUsers);
        register("WRITE_ACTION", this::writeAction);
    }

    private void register(String intent, BiFunction<IntentContext,String,String> operator) { operators.put(intent, operator); }
    public boolean canHandle(String intent) { return operators.containsKey(intent); }
    public Set<String> supportedIntents() { return Collections.unmodifiableSet(operators.keySet()); }

    public Optional<String> execute(String intent, String email, UUID projectId, String question) {
        BiFunction<IntentContext,String,String> operator = operators.get(intent);
        if (operator == null) return Optional.empty(); // EXTERNAL_COMPLEX and UNKNOWN remain external.
        try {
            return Optional.ofNullable(operator.apply(new IntentContext(email, projectId, question), question));
        } catch (IllegalArgumentException e) {
            // These messages are written for the user (e.g. "Which project do you mean?").
            return Optional.of(e.getMessage() == null || e.getMessage().isBlank() ? GENERIC_ERROR : e.getMessage());
        } catch (ResponseStatusException e) {
            String reason = e.getReason();
            return Optional.of(reason == null || reason.isBlank()
                    ? "It looks like you don't have access to that. 🔒 Ask the project owner if you think this is a mistake."
                    : reason);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(LocalIntentEngine.class).warn(
                    "Local intent operator failed for intent {}: {}", intent, e.getClass().getSimpleName());
            String n = e.getClass().getSimpleName();
            if (n.contains("Access") || n.contains("Forbidden") || n.contains("Unauthorized")) {
                return Optional.of("It looks like you don't have access to that. 🔒 Ask the project owner if you think this is a mistake.");
            }
            return Optional.of(GENERIC_ERROR);
        }
    }

    /* =====================================================================
       ACCOUNT
    ===================================================================== */

    private String greeting(IntentContext c, String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(bye|goodbye|see you|good night)\\b.*")) return "Goodbye! 👋 I'm here whenever you need me.";
        if (lower.matches(".*\\b(thanks|thank you|thx)\\b.*")) return "You're very welcome! 😊 Anything else I can look up for you?";
        String name = firstName(c.email());
        return "Hey" + (name.isBlank() ? "" : " " + name) + "! 👋 I'm COBUG AI. I can look things up for you in COBUG.\n\n"
                + block("list", "Try asking me", List.of(
                        row("Show my projects", "Everything you are part of"),
                        row("What are my tasks?", "Your personal to-do list"),
                        row("Any new notifications?", "Unread and recent updates"),
                        row("Who is in this project?", "Members and their roles"),
                        row("Show the project roadmap", "Milestones and progress"),
                        row("Show recent activity", "What your team did lately")));
    }

    private String profile(IntentContext c, String q) {
        User u = user(c.email());
        return reply("Here's your COBUG profile:", block("facts", "Your profile", List.of(
                row("Name", u.getName()),
                row("Email", u.getEmail()),
                row("Role", nice(str(u.getRole()))),
                row("Member since", when(u.getCreatedAt())))), null);
    }

    private String myProjects(IntentContext c, String q) {
        Collection<Project> list = visibleProjects(user(c.email())).values();
        if (list.isEmpty()) return "You don't have any projects yet. When you create or join one, it will show up here. 🚀";
        List<String> rows = new ArrayList<>();
        for (Project p : list) {
            if (rows.size() >= 50) break;
            rows.add(row(p.getName(), shorten(p.getDescription(), 90), nice(str(p.getStatus()))));
        }
        return reply("You're part of **" + plural(list.size(), "project") + "**. Here they are:",
                block("list", "Your projects", rows),
                "Tell me a project's name and I can show its tasks, members, roadmap and more.");
    }

    private String myTasks(IntentContext c, String q) {
        List<PersonalTask> tasks = personalTasks.findByUserIdOrderByCreatedAtDesc(user(c.email()).getId());
        if (tasks.isEmpty()) return "You don't have any personal tasks yet. ✅ Add one from your dashboard whenever you're ready.";
        List<String> rows = new ArrayList<>();
        for (PersonalTask t : tasks) {
            if (rows.size() >= 50) break;
            rows.add(row(t.getTitle(), t.getDueDate() == null ? "" : "Due " + when(t.getDueDate()), nice(str(t.getStatus()))));
        }
        return reply("You have **" + plural(tasks.size(), "personal task") + "**:", block("list", "Your tasks", rows), null);
    }

    private String getAiSettings(IntentContext c, String q) {
        Map<String,Object> dto = aiSettings.dto(c.email());
        String plan = dto.get("plan") == null ? "go" : String.valueOf(dto.get("plan")).toLowerCase().trim();
        if ("go".equals(plan)) {
            List<String> rows = List.of(
                    row("Current Plan", "COBUG Go (Free)", "ACTIVE"),
                    row("AI Engine", "COBUG Local Intent Engine (Embedded Neural Model)", "LOCAL"),
                    row("Capabilities", "Deterministic roadmap, tasks, team, files, and workspace queries", "UNLIMITED"),
                    row("Dynamic Generative AI", "Upgrade to COBUG Plus to enable Gemini, ChatGPT, or Ollama", "LOCKED")
            );
            return reply("COBUG is currently powered by the **Local Intent Engine** on the **Go plan**:\n\n" +
                    "It runs completely locally on your desktop for zero-cost, instant queries about your projects, tasks, roadmaps, and code files.",
                    block("list", "Your AI Engine & Plan", rows),
                    "💡 *To chat with dynamic generative AI models (like Gemini, OpenAI, or Ollama), upgrade to COBUG Plus in Settings → AI.*");
        }
        String provider = String.valueOf(dto.getOrDefault("provider", "ollama"));
        String model = String.valueOf(dto.getOrDefault("model", "qwen2.5-coder:3b"));
        String credits = String.valueOf(dto.getOrDefault("credits", 0));
        List<String> rows = List.of(
                row("Current Plan", "COBUG " + plan.toUpperCase(), "ACTIVE"),
                row("Active Provider", provider.toUpperCase(), "CONNECTED"),
                row("Active Model", model, "MODEL"),
                row("Cloud Credits", credits, "CREDITS")
        );
        return reply("Here is your active AI configuration:", block("list", "AI Settings", rows), null);
    }

    private String searchUsers(IntentContext c, String q) {
        String term = afterAny(q, "search users for", "find users named", "search for user", "find user", "search users", "look up user");
        if (term.isBlank()) return "Who should I look for? 🔎 Try: **Search users for Alex**.";
        return show("Here's who I found for **" + term + "**:", "Matching people", social.searchUsers(c.email(), term),
                "I couldn't find anyone matching **" + term + "**. Try a different spelling.");
    }

    /* =====================================================================
       FRIENDS
    ===================================================================== */

    private String friends(IntentContext c, String q) {
        return show("Here are your friends:", "Friends", social.friends(c.email()),
                "You haven't added any friends yet. Use the Connect page to find people. 🤝");
    }

    private String blockedUsers(IntentContext c, String q) {
        return show("Here are the people you've blocked:", "Blocked people", social.blocked(c.email()),
                "You haven't blocked anyone. 👍");
    }

    private String receivedFriendRequests(IntentContext c, String q) {
        return show("These people want to connect with you:", "Friend requests", social.received(c.email()),
                "You have no pending friend requests right now.");
    }

    private String sentFriendRequests(IntentContext c, String q) {
        return show("Here are the requests you've sent:", "Sent requests", social.sent(c.email()),
                "You haven't sent any friend requests that are still pending.");
    }

    private String friendMessages(IntentContext c, String q) {
        List<Map<String,Object>> friends = social.friends(c.email());
        Map<String,Object> target = findNamedMap(friends, afterAny(q, "messages with", "chat with", "conversation with", "messages from", "chat for"));
        if (target == null) {
            if (friends.isEmpty()) return "You don't have any friends to show messages for yet.";
            List<String> rows = new ArrayList<>();
            for (Map<String,Object> f : friends) rows.add(row(f.get("name"), f.get("email")));
            return reply("Whose messages should I show? Pick a friend:", block("list", "Your friends", rows),
                    "For example: **Show messages with Alex**.");
        }
        return show("Here are your recent messages with **" + target.get("name") + "**:", "Messages",
                social.chat(c.email(), UUID.fromString(String.valueOf(target.get("id")))),
                "You haven't exchanged any messages with " + target.get("name") + " yet.");
    }

    private String sharedProjectsWithUser(IntentContext c, String q) {
        List<Map<String,Object>> friends = social.friends(c.email());
        Map<String,Object> target = findNamedMap(friends, afterAny(q, "shared projects with", "projects shared with", "projects with"));
        if (target == null) {
            if (friends.isEmpty()) return "You don't have any friends yet, so there are no shared projects to show.";
            List<String> rows = new ArrayList<>();
            for (Map<String,Object> f : friends) rows.add(row(f.get("name"), f.get("email")));
            return reply("Which friend should I check?", block("list", "Your friends", rows),
                    "For example: **Show projects shared with Alex**.");
        }
        return show("Projects you share with **" + target.get("name") + "**:", "Shared projects",
                social.sharedProjects(c.email(), UUID.fromString(String.valueOf(target.get("id")))),
                "You and " + target.get("name") + " don't share any projects yet.");
    }

    /* =====================================================================
       GITHUB
    ===================================================================== */

    private String githubConnection(IntentContext c, String q) {
        User u = user(c.email());
        var connection = githubConnections.findByUserId(u.getId()).orElse(null);
        if (connection == null) return "Your GitHub account isn't connected yet. Open **Settings → GitHub** to connect it. 🔗";
        return reply("Your GitHub is connected. ✅", block("facts", "GitHub connection", List.of(
                row("Account", connection.getGithubLogin()),
                row("Connected on", when(connection.getConnectedAt())))), null);
    }

    private String githubRepositories(IntentContext c, String q) {
        User u = user(c.email());
        List<String> rows = new ArrayList<>();
        for (Project p : visibleProjects(u).values()) {
            githubRepositories.findByProjectId(p.getId()).ifPresent(r ->
                    rows.add(row(p.getName(), r.getFullName(), "Branch " + str(r.getBranch()))));
        }
        if (rows.isEmpty()) return "None of your projects has a GitHub repository connected yet.";
        return reply("These projects are linked to GitHub:", block("list", "Connected repositories", rows), null);
    }

    private String githubRepository(IntentContext c, String q) {
        Project p = project(c, q);
        return show("Here's the GitHub repository for **" + p.getName() + "**:", "Repository", github.getRepository(p.getId(), c.email()), "No repository is connected to this project.");
    }

    private String githubTree(IntentContext c, String q) {
        Project p = project(c, q);
        return show("Here's what's inside the repository:", "Repository files", github.tree(p.getId(), c.email(), extractPath(q), null), "I couldn't find any files there.");
    }

    private String githubFile(IntentContext c, String q) {
        Project p = project(c, q);
        String path = extractPath(q);
        if (path.isBlank()) return "Which GitHub file should I open? Include its path, like `src/Main.java`.";
        return show("Here's **" + path + "** from GitHub:", path, github.file(p.getId(), c.email(), path, null), "I couldn't open that file.");
    }

    private String githubWorkspace(IntentContext c, String q) {
        Project p = project(c, q);
        return show("Here's your GitHub workspace:", "GitHub workspace", github.workspace(p.getId(), c.email()), "Your GitHub workspace is empty.");
    }

    /* =====================================================================
       MAIN + VERSIONS
    ===================================================================== */

    private String mainFile(IntentContext c, String q) {
        Project p = project(c, q);
        MainResponse main = mainService.getMain(p.getId(), c.email());
        String path = extractPath(q);
        if (path.isBlank()) {
            if (main.files().isEmpty()) return "Main doesn't have any files yet.";
            List<String> rows = new ArrayList<>();
            for (MainFileResponse f : main.files()) {
                if (rows.size() >= 80) break;
                rows.add(row(f.path(), bytes(f.size())));
            }
            return reply("Main has **" + plural(main.files().size(), "file") + "**:", block("list", "Main files", rows),
                    "Ask for one by name, like **Show Main file src/App.jsx**.");
        }
        byte[] bytes = mainService.readFile(p.getId(), path, c.email());
        String content = new String(bytes, StandardCharsets.UTF_8);
        return reply("Here's **" + path + "** from Main:", fence(path, truncate(content, 12000)), null);
    }

    private String mainVersionList(IntentContext c, String q) {
        Project p = project(c, q);
        List<MainVersion> rows = mainVersionRepository.findByProjectIdOrderByVersionNumberDesc(p.getId());
        if (rows.isEmpty()) return "There are no saved Main versions for this project yet.";
        List<String> out = new ArrayList<>();
        for (MainVersion v : rows) {
            if (out.size() >= 30) break;
            out.add(row("Version " + v.getVersionNumber(), join(" · ", shorten(v.getMessage(), 80), when(v.getCreatedAt()))));
        }
        return reply("Here are the saved versions of **" + p.getName() + "**:", block("list", "Main versions", out),
                "Want to look inside one? Try **Show version 3**.");
    }

    private String mainVersion(IntentContext c, String q) {
        Project p = project(c, q);
        MainVersion v = resolveVersion(p, q);
        return show("Here are the details of **version " + v.getVersionNumber() + "**:", "Version " + v.getVersionNumber(),
                mainVersions.get(p.getId(), v.getId(), c.email()), "I couldn't load that version.");
    }

    private String mainVersionContent(IntentContext c, String q) {
        Project p = project(c, q);
        MainVersion v = resolveVersion(p, q);
        Map<String,Object> detail = mainVersions.get(p.getId(), v.getId(), c.email());
        Object raw = detail.get("files");
        if (!(raw instanceof List<?> files) || files.isEmpty()) return "This version doesn't have any files.";
        String path = extractPath(q);
        if (!path.isBlank()) {
            for (Object item : files) {
                if (item instanceof Map<?,?> f && path.equalsIgnoreCase(String.valueOf(f.get("path")))) {
                    Object content = f.get("content");
                    if (content == null) return "**" + path + "** is stored outside the database or is a binary file, so I can't show it here.";
                    return reply("Here's **" + path + "** from version " + v.getVersionNumber() + ":", fence(path, truncate(String.valueOf(content), 10000)), null);
                }
            }
        }
        List<String> rows = new ArrayList<>();
        for (Object item : files) if (item instanceof Map<?,?> f) rows.add(row(f.get("path")));
        return reply("Version " + v.getVersionNumber() + " has **" + plural(rows.size(), "file") + "**:",
                block("list", "Files in version " + v.getVersionNumber(), rows),
                "Name a file to open it, like **Show version " + v.getVersionNumber() + " file src/App.jsx**.");
    }

    /* =====================================================================
       NOTIFICATIONS
    ===================================================================== */

    private String getNotifications(IntentContext c, String q) {
        User u = user(c.email());
        List<Notification> list = notifications.findByRecipientIdOrderByCreatedAtDesc(u.getId());
        if (list.isEmpty()) return "You have no notifications. 🎉";
        List<String> rows = new ArrayList<>();
        for (Notification n : list) {
            if (rows.size() >= 30) break;
            rows.add(row(n.getTitle(), join(" · ", shorten(n.getMessage(), 100), when(n.getCreatedAt())), n.isSeen() ? "" : "New"));
        }
        return reply("Here are your latest notifications:", block("list", "Notifications", rows), null);
    }

    private String unreadNotificationCount(IntentContext c, String q) {
        long n = notifications.countByRecipientIdAndSeenFalse(user(c.email()).getId());
        if (n == 0) return "You're all caught up. 🎉 No unread notifications.";
        return "You have **" + plural(n, "unread notification") + "**. 🔔";
    }

    /* =====================================================================
       PROJECT
    ===================================================================== */

    private String projectActivity(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        var list = activities.findTop200ByProjectIdOrderByCreatedAtDesc(p.getId());
        if (list.isEmpty()) return "No activity has been recorded for **" + p.getName() + "** yet.";
        List<String> rows = new ArrayList<>();
        for (var a : list) {
            if (rows.size() >= 40) break;
            rows.add(row(nice(str(a.getAction())),
                    join(" · ", shorten(a.getDetails(), 80), safe(a.getActorName(), "A member"), when(a.getCreatedAt()))));
        }
        return reply("Here's what's been happening in **" + p.getName() + "**:", block("list", "Recent activity", rows), null);
    }

    private String projectAim(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        String raw = safe(p.getAimDetails(), "");
        if (raw.isBlank() || raw.equals("{}")) return "**" + p.getName() + "** doesn't have an aim written yet.";
        try {
            Map<String,Object> m = mapper.readValue(raw, new TypeReference<LinkedHashMap<String,Object>>() {});
            return reply("Here's the aim of **" + p.getName() + "**:", composite("Project aim", m), null);
        } catch (Exception e) {
            return reply("Here's the aim of **" + p.getName() + "**:", shorten(raw, 1500), null);
        }
    }

    private String projectChat(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        var list = projectChats.findTop100ByProjectIdOrderByCreatedAtDesc(p.getId());
        if (list.isEmpty()) return "There are no messages in this project's chat yet.";
        List<ProjectChatMessage> ordered = new ArrayList<>(list);
        Collections.reverse(ordered);
        List<String> rows = new ArrayList<>();
        for (ProjectChatMessage m : ordered) {
            if (rows.size() >= 60) break;
            rows.add(row(m.getSenderName(), join(" · ", shorten(m.getContent(), 160), when(m.getCreatedAt()))));
        }
        return reply("Here are the latest messages from the team:", block("list", "Project chat", rows), null);
    }

    private String projectContributions(IntentContext c, String q) {
        Project p = project(c, q);
        return show("Here's who contributed to **" + p.getName() + "**:", "Contributions",
                reviewService.contribution(p.getId(), c.email()), "There are no contributions to show yet.");
    }

    private String projectDetails(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        User owner = users.findById(p.getOwner().getId()).orElse(null);
        return reply("Here are the details of **" + p.getName() + "**:", block("facts", p.getName(), List.of(
                row("Status", nice(str(p.getStatus()))),
                row("About", safe(p.getDescription(), "No description yet")),
                row("Owner", owner == null ? "Unknown" : owner.getName()),
                row("Created", when(p.getCreatedAt())))), null);
    }

    private String projectFileTree(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        List<ProjectFile> list = projectFiles.findProjectTree(p.getId());
        if (list.isEmpty()) return "This project's file tree is empty. You can check **Main** or **Workspace** for the source files.";
        List<String> rows = new ArrayList<>();
        for (ProjectFile f : list) {
            if (rows.size() >= 200) break;
            rows.add(row(f.getPath(), "", nice(str(f.getType()))));
        }
        return reply("**" + p.getName() + "** has **" + plural(list.size(), "item") + "** in its file tree:", block("list", "Project files", rows), null);
    }

    private String projectFile(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        String path = extractPath(q);
        if (path.isBlank()) return "Which project file should I read? Include its path, like `src/App.jsx`.";
        ProjectFile f = projectFiles.findByProjectAndPath(p.getId(), path).orElse(null);
        if (f == null) return "I couldn't find **" + path + "** in the project files. Try asking for the Main or Workspace file instead.";
        return reply("Here's **" + path + "**:", fence(path, truncate(safe(f.getContent(), ""), 12000)), null);
    }

    private String projectInvitations(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireOwner(p.getId(), c.email());
        var list = invitations.findByProjectIdInOrderByCreatedAtDesc(List.of(p.getId()));
        if (list.isEmpty()) return "No invitations have been sent for **" + p.getName() + "** yet.";
        List<String> rows = new ArrayList<>();
        for (var i : list) {
            if (rows.size() >= 50) break;
            rows.add(row(i.getEmail(), join(" · ", "As " + nice(str(i.getRole())), when(i.getCreatedAt())), nice(str(i.getStatus()))));
        }
        return reply("Here are the invitations for **" + p.getName() + "**:", block("list", "Invitations", rows), null);
    }

    private String projectMain(IntentContext c, String q) {
        Project p = project(c, q);
        MainResponse m = mainService.getMain(p.getId(), c.email());
        if (m.files().isEmpty()) return "Main for **" + m.projectName() + "** doesn't have any files yet.";
        List<String> rows = new ArrayList<>();
        for (MainFileResponse f : m.files()) {
            if (rows.size() >= 100) break;
            rows.add(row(f.path(), join(" · ", bytes(f.size())), str(f.language())));
        }
        return reply("Main for **" + m.projectName() + "** has **" + plural(m.files().size(), "file") + "**:", block("list", "Main files", rows), null);
    }

    private String projectMembers(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        var list = members.findByProjectIdOrderByJoinedAtAsc(p.getId());
        if (list.isEmpty()) return "I couldn't find any members for this project.";
        List<String> rows = new ArrayList<>();
        for (var m : list) {
            User u = users.findById(m.getUserId()).orElse(null);
            rows.add(row(u == null ? "Unknown member" : u.getName(), u == null ? "" : u.getEmail(), nice(str(m.getRole()))));
        }
        return reply("**" + p.getName() + "** has **" + plural(rows.size(), "member") + "**:", block("list", "Team members", rows), null);
    }

    private String projectResource(IntentContext c, String q) {
        String path = extractPath(q);
        if (path.isBlank()) return projectResources(c, q);
        Project p = project(c, q);
        String needle = path.toLowerCase(Locale.ROOT);
        return projectResources.findByProjectIdOrderByCreatedAtDesc(p.getId()).stream()
                .filter(r -> r.getOriginalName().equalsIgnoreCase(path) || r.getOriginalName().toLowerCase(Locale.ROOT).contains(needle))
                .findFirst()
                .map(r -> reply("Found it:", block("facts", r.getOriginalName(), List.of(
                        row("Size", bytes(r.getSize())),
                        row("Type", r.getContentType()),
                        row("Uploaded", when(r.getCreatedAt())))), null))
                .orElse("I couldn't find a resource called **" + path + "**.\n\n" + projectResources(c, q));
    }

    private String projectResources(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        var list = projectResources.findByProjectIdOrderByCreatedAtDesc(p.getId());
        if (list.isEmpty()) return "This project doesn't have any uploaded resources yet.";
        List<String> rows = new ArrayList<>();
        for (var r : list) {
            if (rows.size() >= 80) break;
            rows.add(row(r.getOriginalName(), join(" · ", bytes(r.getSize()), r.getContentType())));
        }
        return reply("Here are the resources for **" + p.getName() + "**:", block("list", "Resources", rows), null);
    }

    private String projectRoadmap(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        var stations = roadmapStations.findByProjectIdOrderByPositionAsc(p.getId());
        if (stations.isEmpty()) return "There are no roadmap milestones for **" + p.getName() + "** yet. 🗺️";
        StringBuilder out = new StringBuilder("Here's the roadmap for **" + p.getName() + "**:");
        for (var s : stations) {
            out.append("\n\n**").append(s.getPosition()).append(". ").append(clean(s.getTitle())).append("**");
            String st = nice(str(s.getStatus()));
            if (!st.isBlank()) out.append(" (").append(st).append(")");
            String desc = shorten(s.getDescription(), 200);
            if (!desc.isBlank()) out.append("\n").append(desc);
            List<String> pts = new ArrayList<>();
            for (var pt : roadmapPoints.findByStationIdOrderByPositionAsc(s.getId())) {
                pts.add(row(pt.getTitle(), shorten(pt.getDetails(), 90), nice(str(pt.getStatus()))));
            }
            if (!pts.isEmpty()) out.append("\n\n").append(block("list", "Steps", pts));
        }
        return out.toString();
    }

    private String projectTasks(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        var list = projectTasks.findTop50ByProjectIdAndVisibilityOrderByUpdatedAtDesc(p.getId(), "PUBLIC");
        if (list.isEmpty()) return "There are no public tasks in **" + p.getName() + "** yet.";
        List<String> rows = new ArrayList<>();
        for (var t : list) {
            String who = t.getAssigneeId() == null ? "Not assigned yet"
                    : "Assigned to " + users.findById(t.getAssigneeId()).map(User::getName).orElse("someone");
            rows.add(row(t.getTitle(), who, nice(str(t.getStatus()))));
        }
        return reply("Here are the tasks for **" + p.getName() + "**:", block("list", "Project tasks", rows), null);
    }

    /* =====================================================================
       INVITATIONS
    ===================================================================== */

    private String receivedInvitations(IntentContext c, String q) {
        var list = invitations.findByInvitedUserIdAndStatusOrderByCreatedAtDesc(user(c.email()).getId(), InvitationStatus.PENDING);
        if (list.isEmpty()) return "You have no pending project invitations right now.";
        List<String> rows = new ArrayList<>();
        for (var i : list) {
            Project p = projects.findById(i.getProjectId()).orElse(null);
            rows.add(row(p == null ? "A project" : p.getName(), join(" · ", "As " + nice(str(i.getRole())), when(i.getCreatedAt()))));
        }
        return reply("You've been invited to **" + plural(rows.size(), "project") + "**:", block("list", "Pending invitations", rows),
                "You can accept or decline them from the **Invitations** page.");
    }

    private String sentInvitations(IntentContext c, String q) {
        User u = user(c.email());
        List<UUID> ids = projects.findByOwnerOrderByCreatedAtDesc(u).stream().map(Project::getId).toList();
        if (ids.isEmpty()) return "You don't own any projects yet, so there are no invitations to show.";
        var list = invitations.findByProjectIdInOrderByCreatedAtDesc(ids);
        if (list.isEmpty()) return "You haven't sent any project invitations yet.";
        List<String> rows = new ArrayList<>();
        for (var i : list) {
            if (rows.size() >= 80) break;
            Project p = projects.findById(i.getProjectId()).orElse(null);
            rows.add(row(i.getEmail(), "Invited to " + (p == null ? "a project" : p.getName()), nice(str(i.getStatus()))));
        }
        return reply("Here are the invitations you've sent:", block("list", "Sent invitations", rows), null);
    }

    /* =====================================================================
       REVIEWS
    ===================================================================== */

    private String getReviews(IntentContext c, String q) {
        Project p = project(c, q);
        return show("Here are the reviews for **" + p.getName() + "**:", "Reviews", reviewService.list(p.getId(), c.email()),
                "There are no reviews for **" + p.getName() + "** yet.");
    }

    private String reviewDetails(IntentContext c, String q) {
        Project p = project(c, q);
        UUID id = extractUuid(q);
        if (id == null) {
            List<ReviewRequest> list = reviews.findByProjectIdOrderByCreatedAtDesc(p.getId());
            if (list.isEmpty()) return "This project doesn't have any reviews yet.";
            id = list.get(0).getId();
        }
        return show("Here are the details of the review:", "Review", reviewService.get(p.getId(), id, c.email()), "I couldn't load that review.");
    }

    private String reviewFile(IntentContext c, String q) {
        Project p = project(c, q);
        String path = extractPath(q);
        UUID id = extractUuid(q);
        if (id == null) {
            List<ReviewRequest> list = reviews.findByProjectIdOrderByCreatedAtDesc(p.getId());
            if (list.isEmpty()) return "This project doesn't have any reviews yet.";
            id = list.get(0).getId();
        }
        if (path.isBlank()) return "Which file in the review should I open? Include its path, like `src/App.jsx`.";
        return show("Here's **" + path + "** from the review:", path, reviewService.fileContent(p.getId(), id, path, c.email()), "I couldn't open that file.");
    }

    private String reviewTestPackage(IntentContext c, String q) {
        Project p = project(c, q);
        UUID id = extractUuid(q);
        if (id == null) {
            List<ReviewRequest> list = reviews.findByProjectIdOrderByCreatedAtDesc(p.getId());
            if (list.isEmpty()) return "This project doesn't have a review to test yet.";
            id = list.get(0).getId();
        }
        return show("Here's the test package for the review:", "Test package", reviewService.testPackage(p.getId(), id, c.email(), null), "There's no test package for this review.");
    }

    private String reviewTestPackageDownload(IntentContext c, String q) {
        return "That's a file download, and I can't send files in chat. 📦 Open the review in COBUG and press **Download Test Package**. Nothing was changed.";
    }

    /* =====================================================================
       SOURCE / WORKSPACE
    ===================================================================== */

    private String sourceWorkspace(IntentContext c, String q) {
        Project p = project(c, q);
        projectAccess.requireMember(p.getId(), c.email());
        ProjectSourceWorkspace sw = sourceWorkspaces.findByProjectIdAndUserId(p.getId(), user(c.email()).getId()).orElse(null);
        if (sw == null) return "You don't have a source workspace set up for **" + p.getName() + "** yet.";
        return reply("Here's your source workspace:", block("facts", "Source workspace", List.of(
                row("Local folder", sw.getLocalPath()),
                row("Based on Main version", sw.getBaseMainVersionNumber()),
                row("Last updated", when(sw.getUpdatedAt())))), null);
    }

    private String workspace(IntentContext c, String q) {
        Project p = project(c, q);
        WorkspaceResponse w = workspaceService.getWorkspace(p.getId(), c.email());
        if (w.files().isEmpty()) return "Your workspace for **" + w.projectName() + "** is empty.";
        List<String> rows = new ArrayList<>();
        for (var f : w.files()) {
            if (rows.size() >= 100) break;
            rows.add(row(f.path(), bytes(f.size())));
        }
        return reply("Your workspace for **" + w.projectName() + "** has **" + plural(w.files().size(), "file") + "**:",
                block("list", "Workspace files", rows), null);
    }

    private String workspaceFile(IntentContext c, String q) {
        Project p = project(c, q);
        String path = extractPath(q);
        if (path.isBlank()) return "Which workspace file should I read? Include its path, like `src/App.jsx`.";
        byte[] bytes = workspaceService.readFile(p.getId(), path, c.email());
        return reply("Here's **" + path + "** from your workspace:", fence(path, truncate(new String(bytes, StandardCharsets.UTF_8), 12000)), null);
    }

    private String writeAction(IntentContext c, String q) {
        return "That sounds like a change, and from chat I can only **look things up**, so I haven't touched anything. 🙂\n\n"
                + "You can make the change on the matching page in COBUG. If it helps, I can show you the current details first.";
    }

    /* =====================================================================
       LOOKUP HELPERS
    ===================================================================== */

    private Project project(IntentContext c, String q) {
        User u = user(c.email());
        if (c.projectId() != null) {
            projectAccess.requireMember(c.projectId(), c.email());
            return projects.findById(c.projectId()).orElseThrow(() -> new IllegalArgumentException("I couldn't find that project."));
        }
        LinkedHashMap<UUID,Project> visible = visibleProjects(u);
        String lower = q.toLowerCase(Locale.ROOT);
        List<Project> matches = visible.values().stream().filter(p -> lower.contains(p.getName().toLowerCase(Locale.ROOT))).toList();
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("A few projects match that. Which one do you mean?\n\n"
                    + block("list", "Matching projects", names(matches)));
        }
        if (visible.size() == 1) return visible.values().iterator().next();
        if (visible.isEmpty()) throw new IllegalArgumentException("You don't have any projects yet.");
        throw new IllegalArgumentException("Which project do you mean? Include its name in your message.\n\n"
                + block("list", "Your projects", names(visible.values().stream().limit(20).toList())));
    }

    private List<String> names(List<Project> list) {
        List<String> rows = new ArrayList<>();
        for (Project p : list) rows.add(row(p.getName(), shorten(p.getDescription(), 80)));
        return rows;
    }

    private LinkedHashMap<UUID,Project> visibleProjects(User u) {
        LinkedHashMap<UUID,Project> out = new LinkedHashMap<>();
        projects.findByOwnerOrderByCreatedAtDesc(u).forEach(p -> out.put(p.getId(), p));
        members.findByUserIdOrderByJoinedAtAsc(u.getId()).forEach(m -> projects.findById(m.getProjectId()).ifPresent(p -> out.put(p.getId(), p)));
        return out;
    }

    private MainVersion resolveVersion(Project p, String q) {
        UUID id = extractUuid(q);
        if (id != null) {
            return mainVersionRepository.findById(id).filter(v -> v.getProject().getId().equals(p.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("I couldn't find that version in this project."));
        }
        Matcher m = Pattern.compile("(?i)\\bv(?:ersion)?\\s*#?\\s*(\\d+)\\b").matcher(q);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            return mainVersionRepository.findByProjectIdOrderByVersionNumberDesc(p.getId()).stream()
                    .filter(v -> v.getVersionNumber() == n).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("I couldn't find version " + n + " in this project."));
        }
        return mainVersionRepository.findTopByProjectIdOrderByVersionNumberDesc(p.getId())
                .orElseThrow(() -> new IllegalArgumentException("There are no saved Main versions for this project yet."));
    }

    private User user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("I couldn't find your account. Please sign in again."));
    }

    private String firstName(String email) {
        try {
            String n = users.findByEmail(email).map(User::getName).orElse("");
            return n == null || n.isBlank() ? "" : n.trim().split("\\s+")[0];
        } catch (Exception e) {
            return "";
        }
    }

    private UUID extractUuid(String q) {
        Matcher m = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b").matcher(q);
        return m.find() ? UUID.fromString(m.group()) : null;
    }

    private String extractPath(String q) {
        Matcher quoted = Pattern.compile("[`\"']([^`\"']+)[`\"']").matcher(q);
        if (quoted.find() && quoted.group(1).contains(".")) return quoted.group(1).trim();
        Matcher path = Pattern.compile("(?i)(?:file|path)\\s+(?:named\\s+)?([\\w./\\-]+(?:\\.[\\w]+)?)").matcher(q);
        if (path.find()) return path.group(1).trim();
        Matcher bare = Pattern.compile("(?<![\\w])([\\w.-]+(?:/[\\w.-]+)+\\.[A-Za-z0-9]+)(?![\\w])").matcher(q);
        return bare.find() ? bare.group(1) : "";
    }

    private String afterAny(String q, String... markers) {
        String lower = q.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            int i = lower.indexOf(marker);
            if (i >= 0) return q.substring(i + marker.length()).replaceAll("^[\\s:,-]+|[?.!]+$", "").trim();
        }
        return "";
    }

    private Map<String,Object> findNamedMap(List<Map<String,Object>> rows, String query) {
        if (rows == null || rows.isEmpty() || query == null || query.isBlank()) return null;
        String needle = query.toLowerCase(Locale.ROOT);
        return rows.stream().filter(m -> needle.contains(String.valueOf(m.get("name")).toLowerCase(Locale.ROOT))
                || needle.contains(String.valueOf(m.get("email")).toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    /* =====================================================================
       FRIENDLY FORMATTING
       Everything below turns raw data into clean text for the chat UI.
    ===================================================================== */

    /** Intro sentence + body + optional closing tip. */
    private String reply(String intro, String body, String outro) {
        StringBuilder sb = new StringBuilder();
        if (intro != null && !intro.isBlank()) sb.append(intro).append("\n\n");
        sb.append(body);
        if (outro != null && !outro.isBlank()) sb.append("\n\n").append(outro);
        return sb.toString();
    }

    /** kind = "list" (cards with rows) or "facts" (label/value rows). */
    private String block(String kind, String title, List<String> rows) {
        return ":::" + kind + " " + clean(title) + "\n" + String.join("\n", rows) + "\n:::";
    }

    private String row(Object primary) { return clean(primary); }

    private String row(Object primary, Object secondary) {
        String s = clean(secondary);
        return s.isBlank() ? clean(primary) : clean(primary) + " :: " + s;
    }

    private String row(Object primary, Object secondary, Object badge) {
        String s = clean(secondary), b = clean(badge);
        if (b.isBlank()) return row(primary, s);
        return clean(primary) + " :: " + s + " :: " + b;
    }

    private String fence(String label, String content) {
        return "```" + clean(label) + "\n" + (content == null ? "" : content.replace("```", "'''")) + "\n```";
    }

    /** One line, no block separators. */
    private String clean(Object o) {
        return str(o).replaceAll("\\s*[\\r\\n\\t]+\\s*", " ").replace(":::", ":").replace("::", ":").trim();
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private String join(String sep, Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            String s = str(p).trim();
            if (s.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private String plural(long n, String word) { return n + " " + word + (n == 1 ? "" : "s"); }

    private String safe(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }

    private String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n… [shortened]";
    }

    /** ADMIN / IN_PROGRESS -> "Admin" / "In progress". */
    private String nice(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.trim();
        if (t.matches("[A-Z0-9_]+")) {
            t = t.toLowerCase(Locale.ROOT).replace('_', ' ');
            return Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }

    private String when(Object t) {
        if (t == null) return "";
        String s = String.valueOf(t).trim();
        try {
            if (s.contains("T") && s.matches(".*(Z|[+-]\\d\\d:\\d\\d)$"))
                return DATE_TIME.format(OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()));
        } catch (Exception ignored) { }
        try { return DATE_TIME.format(LocalDateTime.parse(s)); } catch (Exception ignored) { }
        try { return DATE_ONLY.format(LocalDate.parse(s)); } catch (Exception ignored) { }
        return s;
    }

    private String bytes(Object n) {
        if (!(n instanceof Number num)) return "";
        double b = num.longValue();
        if (b < 1024) return (long) b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f KB", b / 1024);
        return String.format(Locale.ENGLISH, "%.1f MB", b / (1024 * 1024));
    }

    /** "createdAt" -> "Created at". */
    private String label(String key) {
        String t = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? key : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Technical fields the user never needs to see. */
    private boolean hidden(String key) {
        String lk = key.toLowerCase(Locale.ROOT);
        return key.equals("id") || key.endsWith("Id") || key.endsWith("_id") || lk.endsWith("uuid")
                || lk.contains("token") || lk.contains("password") || lk.contains("secret")
                || lk.contains("apikey") || lk.endsWith("sha") || lk.equals("encoding");
    }

    private String value(String key, Object v) {
        if (v == null) return "";
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        boolean isDate = key.matches(".*([a-z]At|Date|Time)$") || key.endsWith("_at") || key.equalsIgnoreCase("date");
        if (isDate) return when(v);
        if (key.equalsIgnoreCase("size") && v instanceof Number) return bytes(v);
        return shorten(nice(str(v)), 120);
    }

    private Map<String,Object> asMap(Object o) {
        try {
            return mapper.convertValue(o, new TypeReference<LinkedHashMap<String,Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** Turns any service result (list, map, DTO) into friendly cards. */
    private String show(String intro, String heading, Object value, String empty) {
        if (value instanceof Optional<?> o) value = o.orElse(null);
        if (value == null) return empty;
        if (value instanceof Collection<?> col) {
            if (col.isEmpty()) return empty;
            return reply(intro, collectionBlock(heading, col), null);
        }
        Map<String,Object> m = asMap(value);
        if (m == null) return reply(intro, shorten(str(value), 1500), null);
        return reply(intro, composite(heading, m), null);
    }

    private String collectionBlock(String heading, Collection<?> col) {
        List<String> rows = new ArrayList<>();
        for (Object o : col) {
            if (rows.size() >= 60) break;
            if (o == null) continue;
            boolean simple = o instanceof CharSequence || o instanceof Number || o instanceof Boolean;
            Map<String,Object> m = simple ? null : asMap(o);
            rows.add(m == null ? row(str(o)) : rowFromMap(m));
        }
        return block("list", heading, rows);
    }

    /** One map -> one list row: main text, short detail line, optional badge. */
    private String rowFromMap(Map<String,Object> m) {
        // Chat messages: "Sender :: message"
        if (m.get("content") != null && m.get("senderName") != null) {
            return row(m.get("senderName"), join(" · ", shorten(str(m.get("content")), 160), when(m.get("createdAt"))));
        }
        String titleKey = null, title = null;
        for (String k : TITLE_KEYS) {
            Object v = m.get(k);
            if (v != null && !str(v).isBlank()) { titleKey = k; title = shorten(str(v), 100); break; }
        }
        String badgeKey = null, badge = null;
        for (String k : BADGE_KEYS) {
            Object v = m.get(k);
            if (v instanceof CharSequence || v instanceof Enum<?> || v instanceof Number) { badgeKey = k; badge = shorten(nice(str(v)), 24); break; }
        }
        List<String> bits = new ArrayList<>();
        for (Map.Entry<String,Object> e : m.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.equals(titleKey) || k.equals(badgeKey) || hidden(k) || v == null) continue;
            if (v instanceof Map || v instanceof Collection) continue;
            String s = value(k, v);
            if (s.isBlank()) continue;
            bits.add(isDateKey(k) ? s : label(k) + ": " + s);
            if (bits.size() == 2) break;
        }
        if (title == null) title = "Item";
        return row(title, String.join(" · ", bits), badge);
    }

    private boolean isDateKey(String k) { return k.matches(".*([a-z]At|Date|Time)$") || k.endsWith("_at"); }

    /** One map -> a facts card, plus extra cards for any lists / file content inside it. */
    private String composite(String heading, Map<String,Object> m) {
        List<String> facts = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String,Object> e : m.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null || hidden(k)) continue;
            if (k.equalsIgnoreCase("content") && v instanceof String s) {
                if ("base64".equalsIgnoreCase(str(m.get("encoding")))) {
                    try { s = new String(Base64.getMimeDecoder().decode(s), StandardCharsets.UTF_8); } catch (Exception ignored) { }
                }
                extra.add(fence(heading, truncate(s, 8000)));
            } else if (v instanceof Collection<?> col) {
                if (!col.isEmpty()) extra.add(collectionBlock(label(k), col));
            } else if (v instanceof Map<?,?>) {
                Map<String,Object> sub = asMap(v);
                if (sub != null) {
                    for (String tk : TITLE_KEYS) {
                        if (sub.get(tk) != null && !str(sub.get(tk)).isBlank()) { facts.add(row(label(k), shorten(str(sub.get(tk)), 100))); break; }
                    }
                }
            } else {
                String s = value(k, v);
                if (!s.isBlank()) facts.add(row(label(k), s));
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!facts.isEmpty()) sb.append(block("facts", heading, facts));
        for (String x : extra) { if (sb.length() > 0) sb.append("\n\n"); sb.append(x); }
        return sb.length() == 0 ? "I couldn't find any details to show here." : sb.toString();
    }
}