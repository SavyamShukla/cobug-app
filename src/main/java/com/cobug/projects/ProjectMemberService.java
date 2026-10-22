package com.cobug.projects;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.activity.ProjectActivityService;
import com.cobug.notifications.NotificationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectMemberService {

    private final ProjectMemberRepository memberRepository;
    private final ProjectInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final ProjectActivityService activityService;
    private final NotificationService notificationService;

    @PersistenceContext
    private EntityManager entityManager;

    public ProjectMemberService(
            ProjectMemberRepository memberRepository,
            ProjectInvitationRepository invitationRepository,
            UserRepository userRepository,
            ProjectActivityService activityService,
            NotificationService notificationService
    ) {
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.activityService = activityService;
        this.notificationService = notificationService;
    }

    private UUID currentUserId(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ))
                .getId();
    }

    private UUID projectOwnerId(UUID projectId) {
        try {
            Object value = entityManager
                    .createNativeQuery(
                            "select owner_id from projects where id = :id"
                    )
                    .setParameter("id", projectId)
                    .getSingleResult();

            return UUID.fromString(String.valueOf(value));
        } catch (NoResultException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Project not found."
            );
        }
    }

    private void requireProject(UUID projectId) {
        projectOwnerId(projectId);
    }

    private ProjectMember requireMembership(
            UUID projectId,
            UUID userId
    ) {
        return memberRepository
                .findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "You are not a member of this project."
                ));
    }

    private ProjectMember requireOwner(
            UUID projectId,
            UUID userId
    ) {
        UUID ownerId = projectOwnerId(projectId);

        if (!ownerId.equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only the project owner can perform this action."
            );
        }

        ProjectMember membership =
                memberRepository.findByProjectIdAndUserId(
                        projectId,
                        userId
                ).orElseGet(() -> {
                    ProjectMember owner = new ProjectMember();
                    owner.setProjectId(projectId);
                    owner.setUserId(userId);
                    owner.setRole(ProjectMemberRole.OWNER);
                    owner.setJoinedAt(Instant.now());
                    return memberRepository.save(owner);
                });

        if (membership.getRole() != ProjectMemberRole.OWNER) {
            membership.setRole(ProjectMemberRole.OWNER);
            membership = memberRepository.save(membership);
        }

        return membership;
    }

    @Transactional
    public List<MemberSummary> listMembers(
            UUID projectId,
            String email
    ) {
        UUID userId = currentUserId(email);
        requireProject(projectId);

        // Backfills ownership for projects created before member support.
        if (projectOwnerId(projectId).equals(userId)) {
            requireOwner(projectId, userId);
        } else {
            requireMembership(projectId, userId);
        }

        return memberRepository
                .findByProjectIdOrderByJoinedAtAsc(projectId)
                .stream()
                .map(member -> {
                    User user = userRepository
                            .findById(member.getUserId())
                            .orElse(null);

                    return new MemberSummary(
                            member.getUserId(),
                            user != null ? user.getName() : "COBUG user",
                            user != null ? user.getEmail() : "",
                            member.getRole().name(),
                            member.getJoinedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public List<InvitationSummary> listInvitations(
            UUID projectId,
            String email
    ) {
        UUID userId = currentUserId(email);
        requireOwner(projectId, userId);

        return invitationRepository
                .findByProjectIdAndStatusOrderByCreatedAtDesc(
                        projectId,
                        InvitationStatus.PENDING
                )
                .stream()
                .map(invitation -> new InvitationSummary(
                        invitation.getId(),
                        invitation.getEmail(),
                        invitation.getRole().name(),
                        invitation.getStatus().name(),
                        invitation.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public InvitationSummary invite(
            UUID projectId,
            String email,
            InviteMemberRequest request
    ) {
        UUID ownerId = currentUserId(email);
        requireOwner(projectId, ownerId);

        String invitedEmail = request.email().trim().toLowerCase();
        ProjectMemberRole role = parseAssignableRole(request.role());

        User invitedUser = userRepository
                .findByEmail(invitedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No COBUG account exists for that email yet."
                ));

        if (memberRepository.existsByProjectIdAndUserId(
                projectId,
                invitedUser.getId()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "That user is already a project member."
            );
        }

        if (invitationRepository
                .existsByProjectIdAndEmailIgnoreCaseAndStatus(
                        projectId,
                        invitedEmail,
                        InvitationStatus.PENDING
                )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A pending invitation already exists for this email."
            );
        }

        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setProjectId(projectId);
        invitation.setInvitedUserId(invitedUser.getId());
        invitation.setEmail(invitedEmail);
        invitation.setRole(role);
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setCreatedAt(Instant.now());

        ProjectInvitation saved =
                invitationRepository.save(invitation);

        User owner = userRepository.findById(ownerId).orElseThrow();
        activityService.record(projectId, owner, "INVITED_MEMBER", "MEMBER", invitedUser.getId().toString(), "Invited " + invitedEmail + " as " + role.name());

        Project project = entityManager.find(Project.class, projectId);
        String projectName = project == null ? "a COBUG project" : project.getName();
        notificationService.create(
                invitedUser.getId(),
                ownerId,
                "INVITATION_RECEIVED",
                "Project invitation",
                owner.getName() + " invited you to join " + projectName + " as " + role.name() + ".",
                saved.getId(),
                "PROJECT_INVITATION"
        );

        return new InvitationSummary(
                saved.getId(),
                saved.getEmail(),
                saved.getRole().name(),
                saved.getStatus().name(),
                saved.getCreatedAt()
        );
    }

    @Transactional
    public void updateRole(
            UUID projectId,
            UUID targetUserId,
            String role,
            String email
    ) {
        UUID ownerId = currentUserId(email);
        requireOwner(projectId, ownerId);

        if (ownerId.equals(targetUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The owner role cannot be changed."
            );
        }

        ProjectMember member = requireMembership(
                projectId,
                targetUserId
        );

        member.setRole(parseAssignableRole(role));
        memberRepository.save(member);
        activityService.record(projectId, userRepository.findById(ownerId).orElseThrow(), "CHANGED_MEMBER_ROLE", "MEMBER", targetUserId.toString(), "Changed member role to " + member.getRole().name());
    }

    @Transactional
    public void removeMember(
            UUID projectId,
            UUID targetUserId,
            String email
    ) {
        UUID ownerId = currentUserId(email);
        requireOwner(projectId, ownerId);

        if (ownerId.equals(targetUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The project owner cannot be removed."
            );
        }

        if (!memberRepository.existsByProjectIdAndUserId(
                projectId,
                targetUserId
        )) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Project member not found."
            );
        }

        memberRepository.deleteByProjectIdAndUserId(
                projectId,
                targetUserId
        );
        activityService.record(projectId, userRepository.findById(ownerId).orElseThrow(), "REMOVED_MEMBER", "MEMBER", targetUserId.toString(), "Removed a project member");
    }

    @Transactional
    public void leave(
            UUID projectId,
            String email
    ) {
        UUID userId = currentUserId(email);
        UUID ownerId = projectOwnerId(projectId);

        if (ownerId.equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The project owner cannot leave the project."
            );
        }

        requireMembership(projectId, userId);

        memberRepository.deleteByProjectIdAndUserId(
                projectId,
                userId
        );
        activityService.record(projectId, userRepository.findById(userId).orElseThrow(), "LEFT_PROJECT", "MEMBER", userId.toString(), "Left the project");
    }

    @Transactional
    public void acceptInvitation(
            String token,
            String email
    ) {
        UUID userId = currentUserId(email);

        ProjectInvitation invitation =
                invitationRepository
                        .findByTokenAndStatus(
                                token,
                                InvitationStatus.PENDING
                        )
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Invitation not found or no longer pending."
                        ));

        if (!invitation.getEmail().equalsIgnoreCase(email)
                || !userId.equals(invitation.getInvitedUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "This invitation belongs to another account."
            );
        }

        if (!memberRepository.existsByProjectIdAndUserId(
                invitation.getProjectId(),
                userId
        )) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(invitation.getProjectId());
            member.setUserId(userId);
            member.setRole(invitation.getRole());
            member.setJoinedAt(Instant.now());
            memberRepository.save(member);
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitationRepository.save(invitation);

        Project project = entityManager.find(Project.class, invitation.getProjectId());
        User owner = project == null ? null : project.getOwner();
        User acceptedBy = userRepository.findById(userId).orElseThrow();
        if (owner != null && !owner.getId().equals(userId)) {
            notificationService.create(
                    owner.getId(),
                    userId,
                    "INVITATION_ACCEPTED",
                    "Invitation accepted",
                    acceptedBy.getName() + " accepted your invitation to " + project.getName() + ".",
                    invitation.getId(),
                    "PROJECT_INVITATION"
            );
        }

        notificationService.completeActionNotification(
                userId,
                invitation.getId(),
                "INVITATION_RECEIVED",
                "INVITATION_ACCEPTED",
                "Invitation accepted",
                "You accepted the invitation to join " + project.getName() + "."
        );
    }

    @Transactional
    public void acceptInvitationById(UUID invitationId, String email) {
        UUID userId = currentUserId(email);

        ProjectInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Invitation not found."
                ));

        if (!userId.equals(invitation.getInvitedUserId())
                || !invitation.getEmail().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "This invitation belongs to another account."
            );
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This invitation is no longer pending."
            );
        }

        if (!memberRepository.existsByProjectIdAndUserId(invitation.getProjectId(), userId)) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(invitation.getProjectId());
            member.setUserId(userId);
            member.setRole(invitation.getRole());
            member.setJoinedAt(Instant.now());
            memberRepository.save(member);
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitationRepository.save(invitation);

        Project project = entityManager.find(Project.class, invitation.getProjectId());
        User owner = project == null ? null : project.getOwner();
        User acceptedBy = userRepository.findById(userId).orElseThrow();
        if (owner != null && !owner.getId().equals(userId)) {
            notificationService.create(
                    owner.getId(),
                    userId,
                    "INVITATION_ACCEPTED",
                    "Invitation accepted",
                    acceptedBy.getName() + " accepted your invitation to " + project.getName() + ".",
                    invitation.getId(),
                    "PROJECT_INVITATION"
            );
        }
    }

    @Transactional
    public void rejectInvitation(UUID invitationId, String email) {
        UUID userId = currentUserId(email);

        ProjectInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Invitation not found."
                ));

        if (!userId.equals(invitation.getInvitedUserId())
                || !invitation.getEmail().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "This invitation belongs to another account."
            );
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This invitation is no longer pending."
            );
        }

        invitation.setStatus(InvitationStatus.REJECTED);
        invitationRepository.save(invitation);

        Project project = entityManager.find(Project.class, invitation.getProjectId());
        User owner = project == null ? null : project.getOwner();
        User rejectedBy = userRepository.findById(userId).orElseThrow();
        if (owner != null && !owner.getId().equals(userId)) {
            notificationService.create(
                    owner.getId(),
                    userId,
                    "INVITATION_REJECTED",
                    "Invitation rejected",
                    rejectedBy.getName() + " rejected your invitation to " + project.getName() + ".",
                    invitation.getId(),
                    "PROJECT_INVITATION"
            );
        }

        notificationService.completeActionNotification(
                userId,
                invitation.getId(),
                "INVITATION_RECEIVED",
                "INVITATION_REJECTED",
                "Invitation rejected",
                "You rejected the invitation to join " + project.getName() + "."
        );
    }

    @Transactional
    public void cancelInvitation(
            UUID projectId,
            UUID invitationId,
            String email
    ) {
        UUID ownerId = currentUserId(email);
        requireOwner(projectId, ownerId);

        ProjectInvitation invitation =
                invitationRepository.findById(invitationId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Invitation not found."
                        ));

        if (!projectId.equals(invitation.getProjectId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Invitation not found."
            );
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);
    }

    private ProjectMemberRole parseAssignableRole(String value) {
        try {
            ProjectMemberRole role =
                    ProjectMemberRole.valueOf(
                            value.trim().toUpperCase()
                    );

            if (role == ProjectMemberRole.OWNER) {
                throw new IllegalArgumentException();
            }

            return role;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Role must be ADMIN, DEVELOPER or REVIEWER."
            );
        }
    }
}
