package com.cobug.projects;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invitations")
public class GlobalInvitationController {

    private final ProjectInvitationRepository invitationRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberService memberService;
    private final UserRepository userRepository;

    public GlobalInvitationController(
            ProjectInvitationRepository invitationRepository,
            ProjectRepository projectRepository,
            ProjectMemberService memberService,
            UserRepository userRepository
    ) {
        this.invitationRepository = invitationRepository;
        this.projectRepository = projectRepository;
        this.memberService = memberService;
        this.userRepository = userRepository;
    }

    @GetMapping("/received")
    public List<GlobalInvitationSummary> received(Authentication authentication) {
        User user = currentUser(authentication);
        return invitationRepository
                .findByInvitedUserIdAndStatusOrderByCreatedAtDesc(user.getId(), InvitationStatus.PENDING)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @GetMapping("/sent")
    public List<GlobalInvitationSummary> sent(Authentication authentication) {
        User user = currentUser(authentication);
        List<UUID> projectIds = projectRepository.findByOwnerOrderByCreatedAtDesc(user)
                .stream()
                .map(Project::getId)
                .toList();

        if (projectIds.isEmpty()) return List.of();

        return invitationRepository.findByProjectIdInOrderByCreatedAtDesc(projectIds)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @PostMapping("/{invitationId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@PathVariable UUID invitationId, Authentication authentication) {
        memberService.acceptInvitationById(invitationId, authentication.getName());
    }

    @PostMapping("/{invitationId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID invitationId, Authentication authentication) {
        memberService.rejectInvitation(invitationId, authentication.getName());
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    private GlobalInvitationSummary toSummary(ProjectInvitation invitation) {
        Project project = projectRepository.findById(invitation.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
        User inviter = project.getOwner();
        User recipient = invitation.getInvitedUserId() == null
                ? userRepository.findByEmail(invitation.getEmail()).orElse(null)
                : userRepository.findById(invitation.getInvitedUserId()).orElse(null);

        return new GlobalInvitationSummary(
                invitation.getId(),
                project.getId(),
                project.getName(),
                invitation.getRole().name(),
                invitation.getStatus().name(),
                inviter == null ? null : String.valueOf(inviter.getId()),
                inviter == null ? "Project owner" : inviter.getName(),
                inviter == null ? null : inviter.getEmail(),
                recipient == null ? null : String.valueOf(recipient.getId()),
                recipient == null ? invitation.getEmail() : recipient.getName(),
                recipient == null ? invitation.getEmail() : recipient.getEmail(),
                invitation.getCreatedAt()
        );
    }
}
