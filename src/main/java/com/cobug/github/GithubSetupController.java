package com.cobug.github;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/github")
public class GithubSetupController {
    private final GithubAppService service;
    private final String frontendUrl;
    public GithubSetupController(GithubAppService service,@Value("${cobug.frontend.url:http://localhost:5173}") String frontendUrl){this.service=service;this.frontendUrl=frontendUrl.replaceAll("/$","");}

    @GetMapping("/connection")
    public Map<String,Object> status(Authentication a){return service.status(a.getName());}

    @GetMapping("/repositories")
    public java.util.List<GithubDtos.AccessibleRepository> repositories(Authentication a){return service.accessibleRepositories(a.getName());}

    /** Body (optional): {"client":"desktop"} when the request comes from the Electron desktop app. */
    /** Intelligent connect: reuses an existing GitHub App installation when one exists. */
    @PostMapping("/connect/start")
    public Map<String,Object> connectStart(Authentication a,@RequestBody(required=false) Map<String,String> body){
        boolean desktop=body!=null && "desktop".equalsIgnoreCase(body.get("client"));
        return service.beginConnect(a.getName(),desktop);
    }

    @PostMapping("/install/start")
    public Map<String,Object> start(Authentication a,@RequestBody(required=false) Map<String,String> body){
        boolean desktop=body!=null && "desktop".equalsIgnoreCase(body.get("client"));
        return service.beginInstall(a.getName(),desktop);
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(Authentication a){service.disconnect(a.getName());return ResponseEntity.noContent().build();}

    @GetMapping("/setup")
    public ResponseEntity<String> setup(@RequestParam String state,
                                        @RequestParam(name="installation_id",required=false) Long installationId,
                                        @RequestParam(name="code",required=false) String code){
        boolean intelligent=state!=null && (state.startsWith(GithubAppService.CONNECT_STATE_PREFIX));
        boolean desktop=state!=null && (state.startsWith(GithubAppService.DESKTOP_STATE_PREFIX) || state.startsWith(GithubAppService.CONNECT_DESKTOP_STATE_PREFIX));
        try {
            if(intelligent && installationId==null) {
                Map<String,Object> result=service.completeConnect(state,code);
                if(Boolean.TRUE.equals(result.get("connected"))) {
                    if(desktop) return page(true,null);
                    return ResponseEntity.status(302).location(URI.create(frontendUrl+"/settings?section=github&github=connected")).build();
                }
                Object installUrl=result.get("installUrl");
                if(installUrl!=null) return ResponseEntity.status(302).location(URI.create(String.valueOf(installUrl))).build();
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"GitHub connection could not be completed.");
            }
            if(installationId==null) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"GitHub did not return an installation ID.");
            service.completeInstall(state,installationId,code);
        } catch(ResponseStatusException e) {
            if(desktop) return page(false,e.getReason());
            throw e;
        }
        if(desktop) return page(true,null);
        return ResponseEntity.status(302).location(URI.create(frontendUrl+"/settings?section=github&github=connected")).build();
    }

    private ResponseEntity<String> page(boolean ok,String reason){
        String title=ok?"GitHub connected":"GitHub connection failed";
        String text=ok?"You can close this tab and go back to the COBUG desktop app. It will show GitHub as connected.":HtmlUtils.htmlEscape(reason==null?"Something went wrong. Go back to COBUG and try Connect GitHub again.":reason);
        String html="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>COBUG - "+title+"</title>"
            +"<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0b1020;color:#e2e8f0;font-family:system-ui,Segoe UI,Arial,sans-serif}"
            +".card{max-width:440px;margin:24px;padding:32px;border-radius:20px;border:1px solid rgba(255,255,255,.08);background:rgba(255,255,255,.03);text-align:center}"
            +"h1{font-size:20px;margin:12px 0 8px}p{font-size:14px;line-height:1.6;color:#94a3b8;margin:0}.icon{font-size:38px}</style></head><body><div class=\"card\"><div class=\"icon\">"+(ok?"&#9989;":"&#9888;&#65039;")+"</div>"
            +"<h1>"+title+"</h1><p>"+text+"</p></div></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control","no-store").body(html);
    }
}
