package com.cobug.workspace;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/workspace")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public WorkspaceResponse getWorkspace(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        return workspaceService.getWorkspace(
                projectId,
                authentication.getName()
        );
    }

    @PostMapping(
            value = "/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Void> importFiles(
            @PathVariable UUID projectId,
            @RequestPart("paths") String paths,
            @RequestPart("files") List<MultipartFile> files,
            Authentication authentication
    ) {

        workspaceService.importFiles(
                projectId,
                paths,
                files.toArray(MultipartFile[]::new),
                authentication.getName()
        );

        return ResponseEntity.ok().build();
    }


    public record SaveWorkspaceFile(String path, String content) {}
    public record SaveWorkspaceRequest(List<SaveWorkspaceFile> files) {}

    @PutMapping("/files")
    public ResponseEntity<Void> saveFiles(
            @PathVariable UUID projectId,
            @RequestBody SaveWorkspaceRequest request,
            Authentication authentication
    ) {
        workspaceService.saveFiles(projectId, request == null ? List.of() : request.files(), authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/file")
    public ResponseEntity<ByteArrayResource> readFile(
            @PathVariable UUID projectId,
            @RequestParam String path,
            Authentication authentication
    ) {

        byte[] content = workspaceService.readFile(
                projectId,
                path,
                authentication.getName()
        );

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + safeFileName(path) + "\""
                )
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

    @PutMapping(
            value = "/file",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<Void> updateFile(
            @PathVariable UUID projectId,
            @RequestParam String path,
            @RequestBody byte[] content,
            Authentication authentication
    ) {

        workspaceService.updateFile(
                projectId,
                path,
                content,
                authentication.getName()
        );

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/file")
    public ResponseEntity<Void> deleteFile(
            @PathVariable UUID projectId,
            @RequestParam String path,
            Authentication authentication
    ) {

        workspaceService.deleteFile(
                projectId,
                path,
                authentication.getName()
        );

        return ResponseEntity.noContent().build();
    }

    private String safeFileName(String path) {
        String normalized = String.valueOf(path)
                .replace("\\", "/");
        int index = normalized.lastIndexOf('/');
        return index >= 0
                ? normalized.substring(index + 1)
                : normalized;
    }
}
