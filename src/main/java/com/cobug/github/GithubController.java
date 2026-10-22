package com.cobug.github;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/projects/{projectId}/github")
public class GithubController {
 private final GithubService service; public GithubController(GithubService service){this.service=service;}
 @GetMapping public GithubDtos.RepositoryResponse get(@PathVariable UUID projectId,Authentication a){return service.getRepository(projectId,a.getName());}
 @PostMapping public GithubDtos.RepositoryResponse connect(@PathVariable UUID projectId,@RequestBody GithubDtos.ConnectRequest r,Authentication a){return service.connect(projectId,a.getName(),r);}
 @DeleteMapping public ResponseEntity<Void> disconnect(@PathVariable UUID projectId,Authentication a){service.disconnect(projectId,a.getName());return ResponseEntity.noContent().build();}
 @GetMapping("/tree") public GithubDtos.TreeResponse tree(@PathVariable UUID projectId,@RequestParam(defaultValue="") String path,@RequestParam(required=false) String ref,Authentication a){return service.tree(projectId,a.getName(),path,ref);}
 @GetMapping("/file") public GithubDtos.FileResponse file(@PathVariable UUID projectId,@RequestParam String path,@RequestParam(required=false) String ref,Authentication a){return service.file(projectId,a.getName(),path,ref);}
 @GetMapping("/workspace") public GithubDtos.WorkspaceResponse workspace(@PathVariable UUID projectId,Authentication a){return service.workspace(projectId,a.getName());}
 @PostMapping("/workspace") public GithubDtos.WorkspaceAddResponse add(@PathVariable UUID projectId,@RequestBody GithubDtos.WorkspaceRefRequest r,Authentication a){return service.addToWorkspace(projectId,a.getName(),r.paths());}
 @PutMapping("/file") public GithubDtos.FileResponse saveFile(@PathVariable UUID projectId,@RequestBody Map<String,String> body,Authentication a){return service.saveFile(projectId,a.getName(),body.get("path"),body.get("content"),body.get("sha"),body.get("message"),body.get("ref"));}
 @DeleteMapping("/workspace/data") public ResponseEntity<Void> clearWorkspaceData(@PathVariable UUID projectId,Authentication a){service.clearLegacyWorkspaceData(projectId,a.getName());return ResponseEntity.noContent().build();}
 @DeleteMapping("/workspace") public ResponseEntity<Void> remove(@PathVariable UUID projectId,@RequestParam String path,Authentication a){service.removeWorkspaceFile(projectId,a.getName(),path);return ResponseEntity.noContent().build();}
}
