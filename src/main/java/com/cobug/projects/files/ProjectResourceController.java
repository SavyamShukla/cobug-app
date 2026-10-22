package com.cobug.projects.files;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/resources")
public class ProjectResourceController {
    private final ProjectResourceService service;
    public ProjectResourceController(ProjectResourceService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID projectId,Authentication auth){return service.list(projectId,auth.getName());}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public List<Map<String,Object>> upload(@PathVariable UUID projectId,@RequestPart("files") List<MultipartFile> files,Authentication auth){return service.upload(projectId,auth.getName(),files);}
    @GetMapping("/{resourceId}") public ResponseEntity<Resource> download(@PathVariable UUID projectId,@PathVariable UUID resourceId,Authentication auth){
        ProjectResource item=service.find(projectId,resourceId); Resource resource=service.open(projectId,resourceId,auth.getName());
        MediaType type; try{type=MediaType.parseMediaType(item.getContentType()==null?"application/octet-stream":item.getContentType());}catch(Exception e){type=MediaType.APPLICATION_OCTET_STREAM;}
        return ResponseEntity.ok().contentType(type).contentLength(item.getSize()).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\""+item.getOriginalName().replace("\"","")+"\"").body(resource);
    }
    @DeleteMapping("/{resourceId}") public void delete(@PathVariable UUID projectId,@PathVariable UUID resourceId,Authentication auth){service.delete(projectId,resourceId,auth.getName());}
}
