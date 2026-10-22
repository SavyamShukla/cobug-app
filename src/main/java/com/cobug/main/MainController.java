package com.cobug.main;

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
@RequestMapping("/api/projects/{projectId}/main")
public class MainController {

    private final MainService mainService;

    public MainController(MainService mainService) {
        this.mainService = mainService;
    }

    @GetMapping
    public MainResponse getMain(
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        return mainService.getMain(
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

        mainService.importFiles(
                projectId,
                paths,
                files.toArray(MultipartFile[]::new),
                authentication.getName()
        );

        return ResponseEntity.ok().build();
    }

    @PostMapping("/copy-to-workspace")
    public java.util.Map<String, Object> copyToWorkspace(
            @PathVariable UUID projectId,
            @RequestBody List<String> paths,
            Authentication authentication
    ) {

        int copied = mainService.copyToWorkspace(
                projectId,
                paths,
                authentication.getName()
        );

        return java.util.Map.of(
                "copied", copied
        );
    }

    @GetMapping("/file")
    public ResponseEntity<ByteArrayResource> readFile(
            @PathVariable UUID projectId,
            @RequestParam String path,
            Authentication authentication
    ) {

        byte[] content = mainService.readFile(
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

    @DeleteMapping("/file")
    public ResponseEntity<Void> deleteFile(
            @PathVariable UUID projectId,
            @RequestParam String path,
            Authentication authentication
    ) {

        mainService.deleteFile(
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
