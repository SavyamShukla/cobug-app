package com.cobug.chat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/projects/{projectId}/chat")
public class ProjectChatController {
    private final ProjectChatService service;
    public ProjectChatController(ProjectChatService service){this.service=service;}
    public record SendMessageRequest(String content, String detectedIntent, Double confidence) {}
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID projectId, Authentication auth){return service.list(projectId,auth.getName());}
    @PostMapping public Map<String,Object> send(@PathVariable UUID projectId,@RequestBody SendMessageRequest req,Authentication auth){
        return service.send(projectId,auth.getName(),req==null?null:req.content(),req==null?null:req.detectedIntent(),req==null?null:req.confidence());
    }
    @DeleteMapping("/{messageId}") public void delete(@PathVariable UUID projectId,@PathVariable UUID messageId,Authentication auth){service.delete(projectId,messageId,auth.getName());}
}
