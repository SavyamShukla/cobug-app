package com.cobug.projects;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectMemberController {

    private final ProjectMemberService memberService;

    public ProjectMemberController(ProjectMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/{projectId}/members")
    public List<MemberSummary> members(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        return memberService.listMembers(
                projectId,
                authentication.getName()
        );
    }

    @GetMapping("/{projectId}/invitations")
    public List<InvitationSummary> invitations(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        return memberService.listInvitations(
                projectId,
                authentication.getName()
        );
    }

    @PostMapping("/{projectId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationSummary invite(
            @PathVariable UUID projectId,
            @Valid @RequestBody InviteMemberRequest request,
            Authentication authentication
    ) {
        return memberService.invite(
                projectId,
                authentication.getName(),
                request
        );
    }

    @PatchMapping("/{projectId}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRole(
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            @Valid @RequestBody MemberActionRequest request,
            Authentication authentication
    ) {
        memberService.updateRole(
                projectId,
                userId,
                request.role(),
                authentication.getName()
        );
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            Authentication authentication
    ) {
        memberService.removeMember(
                projectId,
                userId,
                authentication.getName()
        );
    }

    @PostMapping("/{projectId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        memberService.leave(
                projectId,
                authentication.getName()
        );
    }

    @PostMapping("/invitations/{token}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptInvitation(
            @PathVariable String token,
            Authentication authentication
    ) {
        memberService.acceptInvitation(
                token,
                authentication.getName()
        );
    }

    @DeleteMapping("/{projectId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelInvitation(
            @PathVariable UUID projectId,
            @PathVariable UUID invitationId,
            Authentication authentication
    ) {
        memberService.cancelInvitation(
                projectId,
                invitationId,
                authentication.getName()
        );
    }
}
