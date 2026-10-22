package com.cobug.projects;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(
            ProjectService projectService
    ) {
        this.projectService = projectService;
    }

    /*
     * =========================================================
     * CREATE PROJECT
     * =========================================================
     */

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication
    ) {

        return projectService.createProject(
                request,
                authentication.getName()
        );
    }


    /*
     * =========================================================
     * GET MY PROJECTS
     * =========================================================
     */

    @GetMapping
    public List<ProjectResponse> getMyProjects(
            Authentication authentication
    ) {

        return projectService.getMyProjects(
                authentication.getName()
        );
    }


    /*
     * =========================================================
     * GET SINGLE PROJECT
     * =========================================================
     */

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {

        return projectService.getProject(
                projectId,
                authentication.getName()
        );
    }
}