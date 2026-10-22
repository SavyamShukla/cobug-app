package com.cobug.mainversion;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import com.cobug.github.GithubDtos;

@RestController
@RequestMapping("/api/projects/{projectId}/main/versions")
public class MainVersionController {
    private final MainVersionService service;
    public MainVersionController(MainVersionService service){this.service=service;}

    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID projectId,Authentication a){return service.list(projectId,a.getName());}
    @GetMapping("/{versionId}") public Map<String,Object> get(@PathVariable UUID projectId,@PathVariable UUID versionId,Authentication a){return service.get(projectId,versionId,a.getName());}
    @PostMapping public Map<String,Object> create(@PathVariable UUID projectId,@RequestBody MainVersionService.CreateVersionRequest request,Authentication a){return service.create(projectId,a.getName(),request);}
    @DeleteMapping public ResponseEntity<Void> deleteAll(@PathVariable UUID projectId, Authentication a) {
        service.deleteAllMainData(projectId, a.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> uploadFolder(
            @PathVariable UUID projectId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("paths") String[] paths,
            @RequestParam(value = "message", required = false) String message,
            Authentication a
    ) {
        return service.uploadFolder(projectId, a.getName(), files, paths, message);
    }

    @PostMapping(value = "/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> uploadZip(
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "message", required = false) String message,
            Authentication a
    ) {
        return service.uploadZip(projectId, a.getName(), file, message);
    }

    @GetMapping("/{versionId}/download")
    public ResponseEntity<byte[]> downloadZip(
            @PathVariable UUID projectId,
            @PathVariable UUID versionId,
            Authentication a
    ) {
        byte[] bytes = service.downloadZip(projectId, versionId, a.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"main-v" + versionId + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @GetMapping("/current/download")
    public ResponseEntity<byte[]> downloadCurrentMain(
            @PathVariable UUID projectId,
            Authentication a
    ) {
        byte[] bytes = service.downloadCurrentMainZip(projectId, a.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"main-current.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @PostMapping("/merge-review")
    public Map<String,Object> mergeReview(@PathVariable UUID projectId, @RequestBody List<GithubDtos.MergeChange> changes, Authentication a){return service.mergeChangesIntoMain(projectId,a.getName(),changes,"Merged review into COBUG Main");}
    @PostMapping("/push-github")
    public Map<String,Object> pushGithub(@PathVariable UUID projectId,@RequestParam(required=false) String message,Authentication a){return service.pushCurrentMainToGithub(projectId,a.getName(),message);}
    @PostMapping("/snapshot-github") public Map<String,Object> snapshotGithub(@PathVariable UUID projectId,@RequestParam(required=false) String ref,@RequestParam(required=false) String message,Authentication a){return service.snapshotFromGithub(projectId,a.getName(),ref,message);}
    @GetMapping("/{versionId}/materialize") public Map<String,Object> materialize(@PathVariable UUID projectId,@PathVariable UUID versionId,Authentication a){return service.materialize(projectId,versionId,a.getName());}
}

