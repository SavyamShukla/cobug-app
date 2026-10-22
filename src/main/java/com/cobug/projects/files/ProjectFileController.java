package com.cobug.projects.files;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectFileController {

    private final ProjectFileService fileService;

    public ProjectFileController(
            ProjectFileService fileService
    ) {
        this.fileService = fileService;
    }


    // =========================================================
    // GET COMPLETE PROJECT TREE
    // =========================================================

    @GetMapping("/tree")
    public List<ProjectTreeNodeResponse> getTree(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {

        return fileService.getProjectTree(
                projectId,
                authentication.getName()
        );
    }


    // =========================================================
    // CREATE FILE / FOLDER
    // =========================================================

    @PostMapping("/files")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectTreeNodeResponse create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectFileRequest request,
            Authentication authentication
    ) {

        return fileService.create(
                projectId,
                authentication.getName(),
                request
        );
    }


    // =========================================================
    // GET SINGLE FILE
    // =========================================================

    @GetMapping("/files")
    public ProjectTreeNodeResponse getFile(
            @PathVariable UUID projectId,
            @RequestParam String path,
            Authentication authentication
    ) {

        return fileService.getFile(
                projectId,
                path,
                authentication.getName()
        );
    }


    // =========================================================
    // UPDATE FILE CONTENT
    // =========================================================

    @PutMapping("/files")
    public ProjectTreeNodeResponse update(
            @PathVariable UUID projectId,
            @RequestParam String path,
            @RequestBody UpdateProjectFileRequest request,
            Authentication authentication
    ) {

        return fileService.update(
                projectId,
                path,
                authentication.getName(),
                request
        );
    }


    // =========================================================
    // RENAME / MOVE FILE OR FOLDER
    // =========================================================

    @PatchMapping("/files")
    public ProjectTreeNodeResponse rename(
            @PathVariable UUID projectId,
            @RequestParam String path,
            @Valid @RequestBody RenameProjectFileRequest request,
            Authentication authentication
    ) {

        return fileService.rename(
                projectId,
                path,
                authentication.getName(),
                request
        );
    }


    // =========================================================
    // DELETE FILE OR FOLDER
    // =========================================================

    @DeleteMapping("/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID projectId,
            @RequestParam String path,
            Authentication authentication
    ) {

        fileService.delete(
                projectId,
                path,
                authentication.getName()
        );
    }
}
