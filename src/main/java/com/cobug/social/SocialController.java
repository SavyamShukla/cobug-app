package com.cobug.social;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/social")
public class SocialController {
    private final SocialService social;
    public SocialController(SocialService social) { this.social = social; }

    @GetMapping("/search")
    public List<Map<String,Object>> search(@RequestParam(defaultValue="") String q, Authentication a) { return social.searchUsers(a.getName(), q); }

    @PostMapping("/requests/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> request(@PathVariable UUID userId, Authentication a) { return social.sendRequest(a.getName(), userId); }

    @GetMapping("/requests/received")
    public List<Map<String,Object>> received(Authentication a) { return social.received(a.getName()); }

    @GetMapping("/requests/sent")
    public List<Map<String,Object>> sent(Authentication a) { return social.sent(a.getName()); }

    @PostMapping("/requests/{requestId}/accept")
    public Map<String,Object> accept(@PathVariable UUID requestId, Authentication a) { return social.respond(a.getName(), requestId, true); }

    @PostMapping("/requests/{requestId}/reject")
    public Map<String,Object> reject(@PathVariable UUID requestId, Authentication a) { return social.respond(a.getName(), requestId, false); }

    @GetMapping("/friends")
    public List<Map<String,Object>> friends(Authentication a) { return social.friends(a.getName()); }

    @DeleteMapping("/friends/{friendId}")
    public Map<String,Object> unfriend(@PathVariable UUID friendId, Authentication a) { return social.unfriend(a.getName(), friendId); }

    @GetMapping("/blocks/{userId}/shared-projects")
    public Map<String,Object> sharedProjects(@PathVariable UUID userId, Authentication a) { return social.sharedProjects(a.getName(), userId); }

    @PostMapping("/blocks/{userId}")
    public Map<String,Object> block(@PathVariable UUID userId, Authentication a) { return social.block(a.getName(), userId); }

    @DeleteMapping("/blocks/{userId}")
    public Map<String,Object> unblock(@PathVariable UUID userId, Authentication a) { return social.unblock(a.getName(), userId); }

    @GetMapping("/blocks")
    public List<Map<String,Object>> blocked(Authentication a) { return social.blocked(a.getName()); }

    @GetMapping("/friends/{friendId}/messages")
    public List<Map<String,Object>> messages(@PathVariable UUID friendId, Authentication a) { return social.chat(a.getName(), friendId); }

    @PostMapping("/friends/{friendId}/messages")
    public Map<String,Object> message(@PathVariable UUID friendId, @RequestBody Map<String,String> body, Authentication a) { return social.sendMessage(a.getName(), friendId, body == null ? "" : body.get("content")); }
}
