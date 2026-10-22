package com.cobug.projects;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.storage.ProjectStorageService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectStorageService storageService;
    private final ProjectMemberRepository memberRepository;
    private final com.cobug.activity.ProjectActivityService activityService;
    private final RoadmapStationRepository roadmapStationRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            ProjectStorageService storageService,
            ProjectMemberRepository memberRepository,
            com.cobug.activity.ProjectActivityService activityService,
            RoadmapStationRepository roadmapStationRepository
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.memberRepository = memberRepository;
        this.activityService = activityService;
        this.roadmapStationRepository = roadmapStationRepository;
    }


    /*
     * =========================================================
     * CREATE PROJECT
     * =========================================================
     */

    @Transactional
    public ProjectResponse createProject(
            CreateProjectRequest request,
            String userEmail
    ) {

        User owner =
                userRepository.findByEmail(
                        userEmail
                ).orElseThrow(() ->
                        new IllegalArgumentException(
                                "User not found"
                        )
                );

        Project project = new Project();

        project.setName(
                request.name().trim()
        );

        project.setDescription(
                request.description() == null
                        ? null
                        : request.description().trim()
        );

        project.setOwner(owner);
        project.setStatus(ProjectStatus.ACTIVE);

        Project savedProject =
                projectRepository.save(project);

        ProjectMember ownerMembership = new ProjectMember();
        ownerMembership.setProjectId(savedProject.getId());
        ownerMembership.setUserId(owner.getId());
        ownerMembership.setRole(ProjectMemberRole.OWNER);
        ownerMembership.setJoinedAt(java.time.Instant.now());
        memberRepository.save(ownerMembership);

        try {
            storageService.initializeMain(savedProject);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Unable to initialize project storage.",
                    e
            );
        }

        activityService.record(savedProject.getId(), owner, "CREATED_PROJECT", "PROJECT", savedProject.getId().toString(), "Created project " + savedProject.getName());
        return toResponse(savedProject);
    }


    /*
     * =========================================================
     * GET MY PROJECTS
     * =========================================================
     */

    @Transactional(readOnly = true)
    public List<ProjectResponse> getMyProjects(
            String userEmail
    ) {

        User owner =
                userRepository.findByEmail(
                        userEmail
                ).orElseThrow(() ->
                        new IllegalArgumentException(
                                "User not found"
                        )
                );

        java.util.LinkedHashMap<UUID, Project> visible = new java.util.LinkedHashMap<>();
        projectRepository.findByOwnerOrderByCreatedAtDesc(owner)
                .forEach(project -> visible.put(project.getId(), project));
        memberRepository.findByUserIdOrderByJoinedAtAsc(owner.getId()).forEach(member ->
                projectRepository.findById(member.getProjectId()).ifPresent(project -> visible.put(project.getId(), project))
        );
        return visible.values().stream().map(this::toResponse).toList();
    }


    /*
     * =========================================================
     * GET SINGLE PROJECT
     * =========================================================
     */

    @Transactional(readOnly = true)
    public ProjectResponse getProject(
            UUID projectId,
            String userEmail
    ) {

        User currentUser =
                userRepository.findByEmail(
                        userEmail
                ).orElseThrow(() ->
                        new IllegalArgumentException(
                                "User not found"
                        )
                );

        Project project =
                projectRepository.findById(projectId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Project not found"
                                )
                        );

        /*
         * For the current phase, only the project owner
         * can open the project.
         *
         * Member access will be expanded when the
         * project_members system is connected.
         */

        boolean owner = project.getOwner().getId().equals(currentUser.getId());
        boolean member = memberRepository.existsByProjectIdAndUserId(projectId, currentUser.getId());
        if (!owner && !member) {
            throw new IllegalArgumentException("You do not have access to this project");
        }

        return toResponse(project);
    }


    /*
     * =========================================================
     * RESPONSE MAPPER
     * =========================================================
     */

    private ProjectResponse toResponse(
            Project project
    ) {

        long totalStations = roadmapStationRepository.countByProjectId(project.getId());
        long completedStations = roadmapStationRepository.countByProjectIdAndStatus(project.getId(), "DONE");
        int progress = totalStations == 0 ? 0 : (int) Math.round(completedStations * 100.0 / totalStations);

        return new ProjectResponse(
                project.getId().toString(),
                project.getName(),
                project.getDescription(),
                project.getOwner().getId().toString(),
                project.getOwner().getName(),
                project.getStatus().name(),
                project.getCreatedAt(),
                progress,
                totalStations,
                completedStations
        );
    }
}