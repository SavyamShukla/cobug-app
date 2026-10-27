package com.cobug.github;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class GithubAppService {
    private static final Logger log=LoggerFactory.getLogger(GithubAppService.class);
    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final GithubInstallStateRepository states;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    public static final String DESKTOP_STATE_PREFIX="desktop.";
    public static final String CONNECT_STATE_PREFIX="connect.";
    public static final String CONNECT_DESKTOP_STATE_PREFIX="connect.desktop.";
    private final String appId;
    private final String appSlug;
    private final String privateKeyPem;
    private final String clientId;
    private final String clientSecret;
    private final String frontendUrl;

    public GithubAppService(UserRepository users, GithubConnectionRepository connections, GithubInstallStateRepository states,
                            ObjectMapper mapper,
                            @Value("${cobug.github.app.id:}") String appId,
                            @Value("${cobug.github.app.slug:cobug}") String appSlug,
                            @Value("${cobug.github.app.private-key:}") String privateKeyPem,
                            @Value("${cobug.github.app.client-id:}") String clientId,
                            @Value("${cobug.github.app.client-secret:}") String clientSecret,
                            @Value("${cobug.frontend.url:http://localhost:5173}") String frontendUrl) {
        this.users=users; this.connections=connections; this.states=states; this.mapper=mapper;
        this.appId=appId==null?"":appId.trim(); this.appSlug=appSlug==null?"cobug":appSlug.trim(); this.privateKeyPem=privateKeyPem==null?"":privateKeyPem; this.clientId=clientId==null?"":clientId.trim(); this.clientSecret=clientSecret==null?"":clientSecret.trim(); this.frontendUrl=frontendUrl.replaceAll("/$","");
    }

    /**
     * Starts the intelligent Connect/Reconnect flow. It first authorizes the GitHub App
     * for the current GitHub user. The callback can reuse an already-installed App,
     * so disconnecting COBUG does not force the user to reinstall the GitHub App.
     * If the App is not installed yet, the callback falls through to the normal
     * installation flow.
     */
    @Transactional
    public Map<String,Object> beginConnect(String email, boolean desktop) {
        requireConfigured();
        if(clientId.isBlank() || clientSecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"GitHub reconnect requires GITHUB_APP_CLIENT_ID and GITHUB_APP_CLIENT_SECRET on the COBUG backend.");
        User user=users.findByEmail(email).orElseThrow(()->bad("User not found."));
        GithubInstallState s=new GithubInstallState();
        s.setUser(user);
        s.setStateValue((desktop?CONNECT_DESKTOP_STATE_PREFIX:CONNECT_STATE_PREFIX)+randomState());
        s.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        s.setConsumed(false);
        states.save(s);
        String url="https://github.com/login/oauth/authorize?client_id="+urlEncode(clientId)+"&state="+urlEncode(s.getStateValue());
        log.info("GitHub connect flow started for COBUG user {} (desktop={})", email, desktop);
        return Map.of("connectUrl",url,"expiresInSeconds",600);
    }

    public Map<String,Object> beginInstall(String email) { return beginInstall(email,false); }

    /** desktop=true marks the one-time state so the setup page can tell the user to return to the desktop app. */
    @Transactional
    public Map<String,Object> beginInstall(String email, boolean desktop) {
        requireConfigured();
        User user=users.findByEmail(email).orElseThrow(()->bad("User not found."));
        GithubInstallState s=new GithubInstallState();
        s.setUser(user); s.setStateValue((desktop?DESKTOP_STATE_PREFIX:"")+randomState()); s.setExpiresAt(LocalDateTime.now().plusMinutes(10)); s.setConsumed(false); states.save(s);
        String url="https://github.com/apps/"+appSlug+"/installations/new?state="+urlEncode(s.getStateValue());
        return Map.of("installUrl",url,"expiresInSeconds",600);
    }

    /** Completes the intelligent Connect/Reconnect OAuth step. */
    @Transactional
    public Map<String,Object> completeConnect(String state, String code) {
        requireConfigured();
        if(state==null || state.isBlank()) throw bad("Missing GitHub connection state.");
        GithubInstallState s=states.findByStateValue(state).orElseThrow(()->bad("Invalid or expired GitHub connection state."));
        if(s.isConsumed() || s.getExpiresAt().isBefore(LocalDateTime.now())) throw bad("This GitHub connection link has expired. Start GitHub connection again from COBUG.");
        if(code==null || code.isBlank()) throw bad("GitHub did not send an authorization code. Start GitHub connection again from COBUG.");
        s.setConsumed(true); states.save(s);

        String userToken=exchangeCodeForUserToken(code);
        JsonNode githubUser=githubWithToken("GET","/user",null,userToken);
        long accountId=githubUser.path("id").asLong(0);
        String login=githubUser.path("login").asText("");
        if(accountId==0 || login.isBlank()) throw bad("GitHub did not return a valid account for the authorization.");

        Long installationId=findUserInstallation(userToken);
        if(installationId!=null) {
            log.info("GitHub connect flow found existing App installation {} for GitHub account @{}", installationId, login);
            // Verify the installation still exposes repositories before restoring the connection.
            JsonNode installation=githubAsApp("GET","/app/installations/"+installationId,null);
            JsonNode account=installation.path("account");
            long installedAccountId=account.path("id").asLong(0);
            String installedLogin=account.path("login").asText("");
            if(installedAccountId!=accountId || installedLogin.isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The COBUG GitHub App installation does not belong to the authorized GitHub account.");
            }
            String token=installationToken(installationId);
            githubWithToken("GET","/installation/repositories?per_page=1",null,token);
            saveConnection(s.getUser(),installationId,installedAccountId,installedLogin);
            log.info("GitHub App installation {} reconnected to COBUG user {}", installationId, s.getUser().getEmail());
            return Map.of("connected",true);
        }

        // The user authorized COBUG but has not installed the App yet. Continue directly to
        // GitHub's installation page with a fresh one-time state for the same COBUG user.
        GithubInstallState installState=new GithubInstallState();
        installState.setUser(s.getUser());
        boolean desktop=state.startsWith(CONNECT_DESKTOP_STATE_PREFIX);
        installState.setStateValue((desktop?DESKTOP_STATE_PREFIX:"")+randomState());
        installState.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        installState.setConsumed(false);
        states.save(installState);
        String installUrl="https://github.com/apps/"+appSlug+"/installations/new?state="+urlEncode(installState.getStateValue());
        log.info("GitHub account @{} authorized COBUG but has no installation; continuing to installation", login);
        return Map.of("connected",false,"needsInstallation",true,"installUrl",installUrl);
    }

    private Long findUserInstallation(String userToken) {
        long wantedAppId;
        try { wantedAppId=Long.parseLong(appId); } catch(NumberFormatException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_APP_ID must be the numeric GitHub App ID."); }
        for(int page=1; page<=10; page++) {
            JsonNode response=githubWithToken("GET","/user/installations?per_page=100&page="+page,null,userToken);
            JsonNode installations=response.path("installations");
            if(!installations.isArray() || installations.size()==0) break;
            for(JsonNode installation:installations) {
                if(installation.path("app_id").asLong(-1)==wantedAppId) return installation.path("id").asLong(-1);
            }
            if(installations.size()<100) break;
        }
        return null;
    }

    private void saveConnection(User user,long installationId,long accountId,String login) {
        GithubConnection c=connections.findByUserId(user.getId()).orElseGet(GithubConnection::new);
        c.setUser(user); c.setInstallationId(installationId); c.setGithubAccountId(accountId); c.setGithubLogin(login); connections.save(c);
    }

    @Transactional
    public void completeInstall(String state, long installationId, String code) {
        requireConfigured();
        if(state==null || state.isBlank()) throw bad("Missing GitHub installation state.");
        GithubInstallState s=states.findByStateValue(state).orElseThrow(()->bad("Invalid or expired GitHub installation state."));
        if(s.isConsumed() || s.getExpiresAt().isBefore(LocalDateTime.now())) throw bad("This GitHub setup link has expired. Start GitHub setup again from COBUG.");
        s.setConsumed(true); states.save(s);
        // SECURITY: installation_id comes from the browser URL, so it must be proven to belong to the
        // GitHub user who is completing this setup. Without this check a user could paste someone else's
        // installation_id and gain access to that person's repositories.
        verifyInstallationBelongsToUser(code, installationId);
        JsonNode installation=githubAsApp("GET","/app/installations/"+installationId,null);
        JsonNode account=installation.path("account");
        long accountId=account.path("id").asLong(0);
        String login=account.path("login").asText("");
        if(accountId==0 || login.isBlank()) throw bad("GitHub did not return a valid installation account.");
        String token=installationToken(installationId);
        // Verify the installation actually exposes repositories before accepting it.
        githubWithToken("GET","/installation/repositories?per_page=1",null,token);
        saveConnection(s.getUser(),installationId,accountId,login);
        log.info("GitHub App installation {} connected to COBUG user {} as @{}", installationId, s.getUser().getEmail(), login);
    }

    public Map<String,Object> status(String email) {
        User user=users.findByEmail(email).orElseThrow(()->bad("User not found."));
        boolean configured = isConfigured();
        List<String> missing = getMissingConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        connections.findByUserId(user.getId()).ifPresentOrElse(c -> {
            result.put("connected", true);
            result.put("githubLogin", c.getGithubLogin());
            result.put("connectedAt", c.getConnectedAt());
        }, () -> {
            result.put("connected", false);
        });
        result.put("configured", configured);
        if (!configured) {
            result.put("missingConfig", missing);
        }
        return result;
    }

    /**
     * Returns only repositories exposed to this user's COBUG GitHub App installation.
     * No GitHub repository list is taken from browser-supplied URLs or personal tokens.
     */
    public List<GithubDtos.AccessibleRepository> accessibleRepositories(String email) {
        User user=users.findByEmail(email).orElseThrow(()->bad("User not found."));
        GithubConnection connection=connections.findByUserId(user.getId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Connect GitHub in Settings before choosing a repository."));
        String token=installationToken(connection.getInstallationId());
        List<GithubDtos.AccessibleRepository> repositories=new ArrayList<>();
        for(int page=1; page<=20; page++) {
            JsonNode response=githubWithToken("GET","/installation/repositories?per_page=100&page="+page,null,token);
            JsonNode items=response.path("repositories");
            if(!items.isArray() || items.size()==0) break;
            for(JsonNode repo:items) {
                String fullName=repo.path("full_name").asText("");
                String owner=repo.path("owner").path("login").asText("");
                String name=repo.path("name").asText("");
                if(fullName.isBlank() || owner.isBlank() || name.isBlank()) continue;
                repositories.add(new GithubDtos.AccessibleRepository(
                        owner,
                        name,
                        fullName,
                        repo.path("private").asBoolean(false),
                        repo.path("default_branch").asText("main"),
                        repo.path("html_url").asText(""),
                        repo.path("updated_at").asText("")
                ));
            }
            if(items.size()<100) break;
        }
        repositories.sort(Comparator.comparing(GithubDtos.AccessibleRepository::fullName,String.CASE_INSENSITIVE_ORDER));
        return repositories;
    }

    @Transactional
    public void disconnect(String email) {
        User user=users.findByEmail(email).orElseThrow(()->bad("User not found."));
        connections.findByUserId(user.getId()).ifPresent(connections::delete);
    }

    public String installationTokenForUser(User user) {
        GithubConnection c=connections.findByUserId(user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Connect GitHub in Settings before using GitHub for this project."));
        return installationToken(c.getInstallationId());
    }

    public String installationTokenForUserId(UUID userId) {
        GithubConnection c=connections.findByUserId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Connect GitHub in Settings before using GitHub for this project."));
        return installationToken(c.getInstallationId());
    }

    /**
     * Returns an installation token for a project-owned GitHub App
     * installation. The caller does not need to have a personal GitHub
     * connection in COBUG.
     */
    public String installationTokenForInstallationId(Long installationId) {
        if (installationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This project does not have a GitHub App installation configured.");
        }
        return installationToken(installationId);
    }

    private String installationToken(long installationId) {
        JsonNode n=githubAsApp("POST","/app/installations/"+installationId+"/access_tokens","{}");
        String token=n.path("token").asText("");
        if(token.isBlank()) throw bad("GitHub did not return an installation access token.");
        return token;
    }

    private JsonNode githubAsApp(String method,String endpoint,String body) { return githubWithToken(method,endpoint,body,"Bearer "+appJwt()); }
    private JsonNode githubWithToken(String method,String endpoint,String body,String auth) {
        try {
            HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("https://api.github.com"+endpoint))
                    .header("Accept","application/vnd.github+json")
                    .header("X-GitHub-Api-Version","2026-03-10")
                    .header("User-Agent","COBUG");
            if(auth!=null && !auth.isBlank()) b.header("Authorization",auth.startsWith("Bearer ")?auth:"Bearer "+auth);
            if("GET".equals(method)) b.GET(); else b.method(method,HttpRequest.BodyPublishers.ofString(body==null?"":body));
            HttpResponse<String> r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()/100!=2) {
                String msg=r.body(); try { msg=mapper.readTree(r.body()).path("message").asText(msg); } catch(Exception ignored) {}
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"GitHub App request failed ("+r.statusCode()+"): "+msg);
            }
            return r.body().isBlank()?mapper.createObjectNode():mapper.readTree(r.body());
        } catch(ResponseStatusException e){throw e;} catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to reach GitHub: "+e.getMessage(),e);}
    }

    private String appJwt() {
        requireConfigured();
        try {
            PrivateKey key=privateKey();
            return Jwts.builder().issuer(appId).issuedAt(Date.from(Instant.now().minusSeconds(30))).expiration(Date.from(Instant.now().plusSeconds(540))).signWith(key,Jwts.SIG.RS256).compact();
        } catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to create the COBUG GitHub App credential.",e);}
    }
    /**
     * Accepts the .pem exactly as GitHub gives it (PKCS#1, "BEGIN RSA PRIVATE KEY") and also PKCS#8
     * ("BEGIN PRIVATE KEY"), so no openssl conversion is needed.
     */
    public String resolvePrivateKeyPem() {
        if (privateKeyPem != null && !privateKeyPem.isBlank()) {
            String candidate = privateKeyPem.trim();
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(candidate);
                if (java.nio.file.Files.exists(p) && !java.nio.file.Files.isDirectory(p)) {
                    return java.nio.file.Files.readString(p);
                }
            } catch (Exception ignored) {}
            return privateKeyPem;
        }
        for (String path : List.of("/etc/secrets/github-app.pem", "/etc/secrets/github-private-key.pem", "/etc/secrets/github.pem")) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(path);
                if (java.nio.file.Files.exists(p) && !java.nio.file.Files.isDirectory(p)) {
                    log.info("Loaded GitHub App private key from Render secret file: {}", path);
                    return java.nio.file.Files.readString(p);
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    public List<String> getMissingConfig() {
        List<String> missing = new ArrayList<>();
        if (appId == null || appId.isBlank()) missing.add("GITHUB_APP_ID");
        if (clientId == null || clientId.isBlank()) missing.add("GITHUB_APP_CLIENT_ID");
        if (clientSecret == null || clientSecret.isBlank()) missing.add("GITHUB_APP_CLIENT_SECRET");
        if (resolvePrivateKeyPem().isBlank()) missing.add("GITHUB_APP_PRIVATE_KEY");
        return missing;
    }

    public boolean isConfigured() {
        return getMissingConfig().isEmpty();
    }

    private PrivateKey privateKey() throws Exception {
        String keyContent = resolvePrivateKeyPem();
        if (keyContent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_APP_PRIVATE_KEY is missing or empty.");
        }
        String raw = keyContent.replace("\\n", "\n").trim();
        boolean pkcs1 = raw.contains("BEGIN RSA PRIVATE KEY");
        String pem = raw.replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "").replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        if (pkcs1) der = pkcs1ToPkcs8(der);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Wraps a PKCS#1 RSAPrivateKey in the PKCS#8 PrivateKeyInfo structure that Java expects. */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] version={0x02,0x01,0x00};
        byte[] rsaAlgorithm={0x30,0x0d,0x06,0x09,0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,0x01,0x01,0x01,0x05,0x00};
        byte[] octet=derTlv(0x04,pkcs1);
        byte[] body=new byte[version.length+rsaAlgorithm.length+octet.length];
        System.arraycopy(version,0,body,0,version.length);
        System.arraycopy(rsaAlgorithm,0,body,version.length,rsaAlgorithm.length);
        System.arraycopy(octet,0,body,version.length+rsaAlgorithm.length,octet.length);
        return derTlv(0x30,body);
    }
    private static byte[] derTlv(int tag,byte[] content) {
        int len=content.length; byte[] lenBytes;
        if(len<128) lenBytes=new byte[]{(byte)len};
        else if(len<256) lenBytes=new byte[]{(byte)0x81,(byte)len};
        else if(len<65536) lenBytes=new byte[]{(byte)0x82,(byte)(len>>8),(byte)len};
        else lenBytes=new byte[]{(byte)0x83,(byte)(len>>16),(byte)(len>>8),(byte)len};
        byte[] out=new byte[1+lenBytes.length+len];
        out[0]=(byte)tag;
        System.arraycopy(lenBytes,0,out,1,lenBytes.length);
        System.arraycopy(content,0,out,1+lenBytes.length,len);
        return out;
    }

    /** Exchanges the one-time OAuth code for a short-lived user token, then checks the installation is one of that user's. */
    private void verifyInstallationBelongsToUser(String code,long installationId) {
        if(clientId.isBlank()||clientSecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"GitHub integration is missing GITHUB_APP_CLIENT_ID / GITHUB_APP_CLIENT_SECRET on the COBUG backend.");
        if(code==null||code.isBlank()) throw bad("GitHub did not send an authorization code. In the GitHub App settings, enable 'Request user authorization (OAuth) during installation', then connect again.");
        String userToken=exchangeCodeForUserToken(code);
        boolean owned=false;
        for(int page=1;page<=10 && !owned;page++) {
            JsonNode res=githubWithToken("GET","/user/installations?per_page=100&page="+page,null,userToken);
            JsonNode list=res.path("installations");
            if(!list.isArray()||list.size()==0) break;
            for(JsonNode i:list) if(i.path("id").asLong(-1)==installationId){owned=true;break;}
            if(list.size()<100) break;
        }
        if(!owned) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This GitHub installation does not belong to your GitHub account.");
    }

    private String exchangeCodeForUserToken(String code) {
        try {
            String form="client_id="+urlEncode(clientId)+"&client_secret="+urlEncode(clientSecret)+"&code="+urlEncode(code);
            HttpRequest req=HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                    .header("Accept","application/json").header("Content-Type","application/x-www-form-urlencoded").header("User-Agent","COBUG")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> r=http.send(req,HttpResponse.BodyHandlers.ofString());
            JsonNode n=mapper.readTree(r.body());
            String token=n.path("access_token").asText("");
            if(r.statusCode()/100!=2||token.isBlank()) throw bad("GitHub rejected the authorization code ("+n.path("error").asText("unknown error")+"). Start GitHub setup again from COBUG.");
            return token; // used once to verify ownership, never stored
        } catch(ResponseStatusException e){throw e;} catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to reach GitHub: "+e.getMessage(),e);}
    }
    private void requireConfigured() {
        List<String> missing = getMissingConfig();
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GitHub integration is not configured on the backend. Missing environment variable(s): " + String.join(", ", missing) + ". Please set them in your Render dashboard."
            );
        }
    }
    private String randomState(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    private String urlEncode(String v){return java.net.URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
}
