package com.cobug.github;

import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectMemberRepository;
import com.cobug.projects.ProjectRepository;
import com.cobug.mainversion.MainVersionService;
import com.cobug.reviews.ReviewCleanupService;
import com.cobug.storage.source.SourceArtifactStorage;
import org.springframework.context.annotation.Lazy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GithubService {
    private static final Logger log = LoggerFactory.getLogger(GithubService.class);
    private final ProjectRepository projectRepository; private final UserRepository userRepository; private final ProjectMemberRepository memberRepository;
    private final GithubRepositoryRepository repoRepository; private final GithubWorkspaceFileRepository workspaceRepository; private final ObjectMapper mapper; private final HttpClient http;
    private final MainVersionService mainVersionService;
    private final GithubAppService githubAppService;
    private final GithubConnectionRepository connectionRepository;
    private final ReviewCleanupService reviewCleanupService;
    private final SourceArtifactStorage sourceArtifactStorage;
    private final long largeFileThresholdBytes;
    private final long maxSourceFileBytes;
    private final long maxSnapshotBytes;
    private final ThreadLocal<UUID> requestProject = new ThreadLocal<>();
    private final ThreadLocal<String> requestEmail = new ThreadLocal<>();

    public GithubService(ProjectRepository projectRepository, UserRepository userRepository, ProjectMemberRepository memberRepository,
                         GithubRepositoryRepository repoRepository, GithubWorkspaceFileRepository workspaceRepository, ObjectMapper mapper,
                         @Lazy MainVersionService mainVersionService, GithubAppService githubAppService, GithubConnectionRepository connectionRepository, ReviewCleanupService reviewCleanupService,
                         SourceArtifactStorage sourceArtifactStorage,
                         @org.springframework.beans.factory.annotation.Value("${cobug.source.large-file-threshold-bytes:10485760}") long largeFileThresholdBytes,
                         @org.springframework.beans.factory.annotation.Value("${cobug.source.max-file-bytes:94371840}") long maxSourceFileBytes,
                         @org.springframework.beans.factory.annotation.Value("${cobug.source.max-snapshot-bytes:943718400}") long maxSnapshotBytes) {
        this.projectRepository=projectRepository; this.userRepository=userRepository; this.memberRepository=memberRepository; this.repoRepository=repoRepository;
        this.workspaceRepository=workspaceRepository; this.mapper=mapper; this.http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(); this.mainVersionService=mainVersionService; this.githubAppService=githubAppService; this.connectionRepository=connectionRepository; this.reviewCleanupService=reviewCleanupService;
        this.sourceArtifactStorage=sourceArtifactStorage; this.largeFileThresholdBytes=largeFileThresholdBytes; this.maxSourceFileBytes=maxSourceFileBytes; this.maxSnapshotBytes=maxSnapshotBytes;
    }

    public GithubDtos.RepositoryResponse getRepository(UUID projectId,String email){ Project p=project(projectId); access(p,email); return repoRepository.findByProjectId(projectId).map(r->new GithubDtos.RepositoryResponse(r.getOwner(),r.getRepository(),r.getFullName(),r.getBranch(),r.getConnectedAt())).orElse(null); }

    @Transactional
    public GithubDtos.RepositoryResponse connect(UUID projectId,String email,GithubDtos.ConnectRequest request){
        setRequestContext(projectId, email);
        Project p=project(projectId); User u=user(email); owner(p,u);
        String full=normalizeRepo(request==null?null:request.repository());
        if(full==null) throw bad("Repository must be in owner/repository format.");
        String[] parts=full.split("/"); String branch=request.branch()==null||request.branch().isBlank()?null:request.branch().trim();
        JsonNode metadata=github("GET", "/repos/"+parts[0]+"/"+parts[1], null);
        String defaultBranch=metadata.path("default_branch").asText("main"); if(branch==null) branch=defaultBranch;
        // Validate branch and permissions before saving the connection.
        github("GET", "/repos/"+parts[0]+"/"+parts[1]+"/git/ref/heads/"+encodePath(branch), null);
        GithubRepository r=repoRepository.findByProjectId(projectId).orElseGet(GithubRepository::new);
        if(r.getId()!=null && !full.equalsIgnoreCase(r.getFullName())) workspaceRepository.deleteByProjectId(projectId);
        GithubConnection ownerConnection = githubConnection(projectId, email);
        r.setProject(p);
        r.setConnection(ownerConnection);
        r.setGithubInstallationId(ownerConnection.getInstallationId());
        r.setOwner(parts[0]);
        r.setRepository(parts[1]);
        r.setFullName(full);
        r.setBranch(branch);
        repoRepository.save(r);
        try {
            mainVersionService.snapshotFromGithub(projectId, email, r.getBranch(), "Initial Main snapshot · " + r.getFullName());
        } catch (RuntimeException ex) {
            log.error("[GITHUB CONNECT] Initial Main snapshot failed for project {}. Rolling back repository connection.", projectId, ex);
            throw ex;
        }
        return new GithubDtos.RepositoryResponse(r.getOwner(),r.getRepository(),r.getFullName(),r.getBranch(),r.getConnectedAt());
    }

    @Transactional
    public void disconnect(UUID projectId,String email){
        Project p=project(projectId);
        owner(p,user(email));

        // GitHub is an optional integration. Disconnecting it removes the repository connection
        // while preserving COBUG's central Main source code, reviews, and project history.
        workspaceRepository.deleteByProjectId(projectId);
        repoRepository.deleteByProjectId(projectId);
        log.info("GitHub repository disconnected from project {}. COBUG central Main and review history are preserved.", projectId);
    }

    public boolean isConnected(UUID projectId) {
        return repoRepository.findByProjectId(projectId).isPresent();
    }

    public void clearLegacyWorkspaceData(UUID projectId, String email) {
        Project p=project(projectId);
        User u=user(email);
        access(p,email);
        workspaceRepository.deleteByProjectIdAndUserId(projectId,u.getId());
    }

    public void requireConnected(UUID projectId) {
        if (!isConnected(projectId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This project is disconnected from GitHub. Review and GitHub push services are unavailable until a repository is connected again.");
        }
    }

    public GithubDtos.TreeResponse tree(UUID projectId,String email,String path,String ref){
        setRequestContext(projectId, email);
        Project p=project(projectId); access(p,email); GithubRepository r=repository(projectId); String branch=ref==null||ref.isBlank()?r.getBranch():ref.trim();
        JsonNode refNode=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/git/ref/heads/"+encodePath(branch),null); String commitSha=refNode.path("object").path("sha").asText("");
        JsonNode root=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/git/trees/"+encodePath(commitSha)+"?recursive=1",null);
        String prefix=normalizePath(path); List<GithubDtos.TreeNode> out=new ArrayList<>(); boolean truncated=root.path("truncated").asBoolean(false);
        for(JsonNode n:root.path("tree")){ if(!"blob".equals(n.path("type").asText())) continue; String pth=n.path("path").asText(); if(!prefix.isBlank() && !(pth.equals(prefix)||pth.startsWith(prefix+"/"))) continue; String rel=prefix.isBlank()?pth:pth.substring(prefix.length()).replaceFirst("^/",""); if(rel.isBlank()) continue; String name=rel.substring(rel.lastIndexOf('/')+1); String lang=language(name); out.add(new GithubDtos.TreeNode(rel,name,"file",n.path("sha").asText(""),n.path("size").asLong(0),lang)); }
        out.sort(Comparator.comparing(GithubDtos.TreeNode::path)); return new GithubDtos.TreeResponse(r.getFullName(),branch,commitSha,truncated,out);
    }

    public GithubDtos.FileResponse file(UUID projectId,String email,String path,String ref){
        setRequestContext(projectId, email);
        Project p=project(projectId); access(p,email); GithubRepository r=repository(projectId); String clean=normalizePath(path); if(clean.isBlank()) throw bad("File path is required."); String branch=ref==null||ref.isBlank()?r.getBranch():ref.trim();
        JsonNode n=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/contents/"+encodePath(clean)+"?ref="+url(branch),null); if(!n.isObject() || !n.has("content")) throw bad("GitHub did not return file content.");
        String encoding=n.path("encoding").asText(""); long size=n.path("size").asLong(0); String sha=n.path("sha").asText(""); boolean binary=!"base64".equalsIgnoreCase(encoding);
        String content=""; if(!binary){ try { String encoded=n.path("content").asText(""); if(encoded.isBlank() && !sha.isBlank()){ JsonNode blob=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/git/blobs/"+encodePath(sha),null); encoded=blob.path("content").asText(""); } byte[] bytes=Base64.getMimeDecoder().decode(encoded.replaceAll("\\s","")); content=new String(bytes,StandardCharsets.UTF_8); } catch(Exception e){ binary=true; } }
        JsonNode refNode=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/git/ref/heads/"+encodePath(branch),null); String commit=refNode.path("object").path("sha").asText(""); return new GithubDtos.FileResponse(r.getFullName(),branch,clean,sha,commit,content,binary,size);
    }

    public GithubDtos.WorkspaceResponse workspace(UUID projectId,String email){ Project p=project(projectId); User u=user(email); access(p,email); GithubRepository r=repository(projectId); List<GithubDtos.WorkspaceFileResponse> files=workspaceRepository.findAllByProjectIdAndUserIdOrderByPathAsc(projectId,u.getId()).stream().map(x->new GithubDtos.WorkspaceFileResponse(x.getId().toString(),x.getRepositoryFullName(),x.getBranch(),x.getPath(),x.getCommitSha(),x.getBlobSha(),x.getBaseMainVersionId(),x.getBaseMainVersionNumber(),x.getAddedAt())).toList(); return new GithubDtos.WorkspaceResponse(r.getFullName(),r.getBranch(),files); }

    /**
     * Add source files to a member Workspace. The important invariant is that
     * Workspace references are pinned to an immutable COBUG Main snapshot.
     * GitHub may move later, but the member keeps the exact Main baseline that
     * existed when the files were added.
     */
    public GithubDtos.WorkspaceAddResponse addToWorkspace(UUID projectId,String email,List<String> paths){
        setRequestContext(projectId, email);
        Project p=project(projectId);
        User u=user(email);
        access(p,email);
        GithubRepository r=repository(projectId);
        if(paths==null||paths.isEmpty()) throw bad("Select at least one file.");

        // Capture the current connected GitHub branch before creating Workspace
        // references. MainVersionService deduplicates an already captured commit.
        Map<String,Object> snapshot=mainVersionService.snapshotFromGithub(
                projectId, email, r.getBranch(),
                "Workspace baseline · " + r.getFullName()
        );
        UUID mainVersionId=UUID.fromString(String.valueOf(snapshot.get("id")));
        long mainVersionNumber=((Number)snapshot.get("versionNumber")).longValue();

        GithubDtos.TreeResponse t=tree(projectId,email,"",r.getBranch());
        Map<String,GithubDtos.TreeNode> byPath=t.files().stream().collect(Collectors.toMap(GithubDtos.TreeNode::path,x->x,(a,b)->a));
        int count=0;
        for(String raw:paths){
            String path=normalizePath(raw);
            GithubDtos.TreeNode n=byPath.get(path);
            if(n==null) throw bad("File not found in repository: "+path);
            GithubWorkspaceFile f=workspaceRepository.findByProjectIdAndUserIdAndPath(projectId,u.getId(),path).orElseGet(GithubWorkspaceFile::new);
            f.setProject(p); f.setUser(u); f.setRepositoryFullName(r.getFullName()); f.setBranch(r.getBranch());
            f.setPath(path); f.setCommitSha(t.commitSha()); f.setBlobSha(n.sha());
            f.setBaseMainVersionId(mainVersionId); f.setBaseMainVersionNumber(mainVersionNumber);
            workspaceRepository.save(f);
            count++;
        }
        return new GithubDtos.WorkspaceAddResponse(count, mainVersionId, mainVersionNumber, t.commitSha());
    }

    public GithubDtos.FileResponse saveFile(UUID projectId,String email,String path,String content,String sha,String message,String ref){
        setRequestContext(projectId, email);
        Project p=project(projectId); access(p,email); GithubRepository r=repository(projectId);
        String clean=normalizePath(path); if(clean.isBlank()) throw bad("File path is required."); if(tokenForRequest().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Connect GitHub in Settings before using GitHub write access.");
        String branch=ref==null||ref.isBlank()?r.getBranch():ref.trim();
        String currentSha=sha;
        if(currentSha==null||currentSha.isBlank()){ JsonNode current=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/contents/"+encodePath(clean)+"?ref="+url(branch),null); currentSha=current.path("sha").asText(""); }
        String commitMessage=(message==null||message.isBlank()?"Update "+clean:message.trim());
        try {
            Map<String,Object> body=new LinkedHashMap<>(); body.put("message",commitMessage); body.put("content",Base64.getEncoder().encodeToString(content==null?new byte[0]:content.getBytes(StandardCharsets.UTF_8))); body.put("sha",currentSha); body.put("branch",branch);
            String json=mapper.writeValueAsString(body); JsonNode saved=github("PUT","/repos/"+r.getOwner()+"/"+r.getRepository()+"/contents/"+encodePath(clean),json);
            String newSha=saved.path("content").path("sha").asText(currentSha); JsonNode refNode=github("GET","/repos/"+r.getOwner()+"/"+r.getRepository()+"/git/ref/heads/"+encodePath(branch),null); String commit=refNode.path("object").path("sha").asText("");
            workspaceRepository.findByProjectIdAndUserIdAndPath(projectId, user(email).getId(), clean).ifPresent(w -> { w.setCommitSha(commit); w.setBlobSha(newSha); w.setBranch(branch); workspaceRepository.save(w); });
            return new GithubDtos.FileResponse(r.getFullName(),branch,clean,newSha,commit,content==null?"":content,false,content==null?0:content.getBytes(StandardCharsets.UTF_8).length);
        } catch(Exception e){ if(e instanceof ResponseStatusException x) throw x; throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to commit the file to GitHub: "+e.getMessage(),e); }
    }

    public void saveReviewFile(UUID projectId, String email, String path, byte[] content, String baseSha, String message) {
        setRequestContext(projectId, email);
        Project p = project(projectId);
        access(p, email);
        GithubRepository r = repository(projectId);
        String clean = normalizePath(path);
        if (clean.isBlank()) throw bad("File path is required.");
        if (tokenForRequest().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Connect GitHub in Settings before using GitHub write access.");
        String branch = r.getBranch();
        try {
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("message", message == null || message.isBlank() ? "COBUG: merge review" : message);
            body.put("content", Base64.getEncoder().encodeToString(content == null ? new byte[0] : content));
            if (baseSha != null && !baseSha.isBlank()) body.put("sha", baseSha);
            body.put("branch", branch);
            String json = mapper.writeValueAsString(body);
            github("PUT", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/contents/" + encodePath(clean), json);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to merge review file into GitHub: " + e.getMessage(), e);
        }
    }

    /**
     * Read the connected GitHub branch as a source-only immutable snapshot.
     * Generated/dependency directories are excluded before the snapshot is returned.
     * This is used to create COBUG Main versions; GitHub remains the external source of truth.
     */
    public String currentCommit(UUID projectId, String email, String ref) {
        setRequestContext(projectId, email);
        Project p = project(projectId);
        access(p, email);
        GithubRepository r = repository(projectId);
        String branch = ref == null || ref.isBlank() ? r.getBranch() : ref.trim();
        JsonNode refNode = github("GET", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/ref/heads/" + encodePath(branch), null);
        String commit = refNode.path("object").path("sha").asText("");
        if (commit.isBlank()) throw bad("Unable to resolve the current GitHub branch commit.");
        return commit;
    }

    public GithubDtos.SnapshotResponse snapshot(UUID projectId, String email, String ref) {
        setRequestContext(projectId, email);
        Project p = project(projectId);
        access(p, email);
        GithubRepository r = repository(projectId);
        String branch = ref == null || ref.isBlank() ? r.getBranch() : ref.trim();
        JsonNode refNode = github("GET", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/ref/heads/" + encodePath(branch), null);
        String commitSha = refNode.path("object").path("sha").asText("");
        if (commitSha.isBlank()) throw bad("Unable to resolve the GitHub branch commit.");

        byte[] archive = githubBytes("GET", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/zipball/" + encodePath(commitSha));
        List<GithubDtos.SourceFile> files = new ArrayList<>();
        long totalBytes = 0;
        final int maxFiles = 5000;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = stripArchiveRoot(entry.getName());
                if (path.isBlank() || SourcePathPolicy.isIgnored(path)) continue;

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                long fileBytes = 0;
                while ((n = zip.read(buffer)) != -1) {
                    fileBytes += n;
                    if (fileBytes > maxSourceFileBytes) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "A project file exceeds COBUG's 90 MB per-file limit: " + path + ". Large project files are stored in COBUG local source storage, but GitHub normal Git blobs remain limited to 100 MiB.");
                    }
                    out.write(buffer, 0, n);
                }
                byte[] bytes = out.toByteArray();
                totalBytes += bytes.length;
                if (totalBytes > maxSnapshotBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "The project snapshot exceeds COBUG's 900 MB snapshot limit.");
                }

                boolean text = SourcePathPolicy.looksText(path, bytes);
                boolean large = bytes.length > largeFileThresholdBytes;
                String content = text && !large ? new String(bytes, StandardCharsets.UTF_8) : null;
                String storageType = (text && !large) ? "DATABASE" : "LOCAL_STORAGE";
                files.add(new GithubDtos.SourceFile(path, content, sha256(bytes), bytes.length, bytes, !text, storageType, null));
                if (files.size() > maxFiles) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The source snapshot contains more than 5000 project-controlled files.");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read the GitHub source archive: " + e.getMessage(), e);
        }
        files.sort(Comparator.comparing(GithubDtos.SourceFile::path));
        return new GithubDtos.SnapshotResponse(r.getFullName(), branch, commitSha, files);
    }

    private byte[] githubBytes(String method, String endpoint) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.github.com" + endpoint))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "COBUG");
            String auth = tokenForRequest();
            if (!auth.isBlank()) b.header("Authorization", "Bearer " + auth);
            b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                String msg = new String(response.body(), StandardCharsets.UTF_8);
                if (response.statusCode() == 401 || response.statusCode() == 403) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub authentication/permission failed: " + msg);
                if (response.statusCode() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub source archive not found or not accessible.");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub archive request failed (" + response.statusCode() + "): " + msg);
            }
            return response.body();
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach GitHub: " + e.getMessage(), e); }
    }

    private String stripArchiveRoot(String value) {
        String normalized = normalizePath(value);
        int slash = normalized.indexOf('/');
        return slash < 0 ? "" : normalized.substring(slash + 1);
    }

    private boolean isSourcePath(String path) {
        return !SourcePathPolicy.isIgnored(path);
    }

    private String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(String.format("%02x", x));
        return b.toString();
    }

    /** Apply an entire review changeset as one Git commit. */
    public GithubDtos.MergeResponse applyChangesAtomically(UUID projectId, String email, List<GithubDtos.MergeChange> changes, String expectedBaseCommit, String message) {
        setRequestContext(projectId, email);
        Project p = project(projectId);
        access(p, email);
        GithubRepository r = repository(projectId);
        if (tokenForRequest().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Connect GitHub in Settings before using GitHub write access.");
        if (changes == null || changes.isEmpty()) throw bad("There are no changes to merge.");

        JsonNode refNode = github("GET", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/ref/heads/" + encodePath(r.getBranch()), null);
        String currentCommit = refNode.path("object").path("sha").asText("");
        if (expectedBaseCommit != null && !expectedBaseCommit.isBlank() && !expectedBaseCommit.equals(currentCommit)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Main moved after this review was submitted. Rebase or resubmit the review before merging.");
        }
        JsonNode currentCommitNode = github("GET", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/commits/" + encodePath(currentCommit), null);
        String baseTree = currentCommitNode.path("tree").path("sha").asText("");
        if (baseTree.isBlank()) throw bad("Unable to resolve the current Main tree.");

        List<Map<String,Object>> treeEntries = new ArrayList<>();
        try {
            for (GithubDtos.MergeChange change : changes) {
                String path = normalizePath(change.path());
                if (path.isBlank()) continue;
                Map<String,Object> entry = new LinkedHashMap<>();
                entry.put("path", path);
                entry.put("mode", "100644");
                entry.put("type", "blob");
                if (change.deleted()) {
                    entry.put("sha", null);
                } else {
                    Map<String,Object> blobBody = new LinkedHashMap<>();
                    if (change.bytes() != null) {
                        blobBody.put("content", Base64.getEncoder().encodeToString(change.bytes()));
                        blobBody.put("encoding", "base64");
                    } else {
                        blobBody.put("content", change.content() == null ? "" : change.content());
                        blobBody.put("encoding", "utf-8");
                    }
                    JsonNode blob = github("POST", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/blobs", mapper.writeValueAsString(blobBody));
                    entry.put("sha", blob.path("sha").asText());
                }
                treeEntries.add(entry);
            }
            Map<String,Object> treeBody = new LinkedHashMap<>();
            treeBody.put("base_tree", baseTree);
            treeBody.put("tree", treeEntries);
            JsonNode tree = github("POST", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/trees", mapper.writeValueAsString(treeBody));
            String treeSha = tree.path("sha").asText("");
            if (treeSha.isBlank()) throw bad("GitHub did not return the merged tree SHA.");

            Map<String,Object> commitBody = new LinkedHashMap<>();
            commitBody.put("message", message == null || message.isBlank() ? "COBUG: merge review" : message.trim());
            commitBody.put("tree", treeSha);
            commitBody.put("parents", List.of(currentCommit));
            JsonNode commit = github("POST", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/commits", mapper.writeValueAsString(commitBody));
            String newCommit = commit.path("sha").asText("");
            if (newCommit.isBlank()) throw bad("GitHub did not return the merge commit SHA.");

            Map<String,Object> refBody = Map.of("sha", newCommit, "force", false);
            github("PATCH", "/repos/" + r.getOwner() + "/" + r.getRepository() + "/git/refs/heads/" + encodePath(r.getBranch()), mapper.writeValueAsString(refBody));
            return new GithubDtos.MergeResponse(r.getFullName(), r.getBranch(), newCommit, treeSha);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create the atomic GitHub merge commit: " + e.getMessage(), e); }
    }

    /** Push the complete current COBUG Main source state to the connected GitHub branch. */
    public GithubDtos.MergeResponse pushSourceState(UUID projectId, String email, List<GithubDtos.SourceFile> desired, String expectedBaseCommit, String message) {
        setRequestContext(projectId, email);
        Project project = project(projectId);
        access(project, email);
        requireConnected(projectId);
        GithubDtos.SnapshotResponse current = snapshot(projectId, email, null);
        if (expectedBaseCommit != null && !expectedBaseCommit.isBlank() && !expectedBaseCommit.equals(current.commitSha())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub changed after the last COBUG Main push. Capture the newer GitHub state into Main before pushing again.");
        }
        Map<String,GithubDtos.SourceFile> currentFiles = current.files().stream().collect(Collectors.toMap(GithubDtos.SourceFile::path, x -> x, (a,b) -> b, TreeMap::new));
        Map<String,GithubDtos.SourceFile> desiredFiles = (desired == null ? List.<GithubDtos.SourceFile>of() : desired).stream().collect(Collectors.toMap(GithubDtos.SourceFile::path, x -> x, (a,b) -> b, TreeMap::new));
        List<GithubDtos.MergeChange> changes = new ArrayList<>();
        for (var entry : desiredFiles.entrySet()) {
            GithubDtos.SourceFile old = currentFiles.get(entry.getKey());
            if (old == null || !Objects.equals(old.sha256(), entry.getValue().sha256())) {
                changes.add(new GithubDtos.MergeChange(entry.getKey(), entry.getValue().content(), null, false, entry.getValue().bytes()));
            }
        }
        for (String path : currentFiles.keySet()) {
            if (!desiredFiles.containsKey(path)) changes.add(new GithubDtos.MergeChange(path, "", null, true));
        }
        if (changes.isEmpty()) return new GithubDtos.MergeResponse(current.repository(), current.branch(), current.commitSha(), "");
        return applyChangesAtomically(projectId, email, changes, current.commitSha(), message == null || message.isBlank() ? "COBUG: push Main" : message.trim());
    }

    public void removeWorkspaceFile(UUID projectId,String email,String path){ Project p=project(projectId); User u=user(email); access(p,email); workspaceRepository.findByProjectIdAndUserIdAndPath(projectId,u.getId(),normalizePath(path)).ifPresent(workspaceRepository::delete); }

    private JsonNode github(String method,String endpoint,String body){ try { HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("https://api.github.com"+endpoint)).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","COBUG"); String auth = tokenForRequest();
        if(!auth.isBlank()) b.header("Authorization","Bearer "+auth); if("GET".equals(method)) b.GET(); else b.method(method,HttpRequest.BodyPublishers.ofString(body==null?"":body)); HttpResponse<String> response=http.send(b.build(),HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2){ String msg; try{JsonNode j=mapper.readTree(response.body());msg=j.path("message").asText(response.body());}catch(Exception e){msg=response.body();} if(response.statusCode()==401||response.statusCode()==403) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"GitHub authentication/permission failed: "+msg); if(response.statusCode()==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"GitHub resource not found or not accessible."); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"GitHub request failed ("+response.statusCode()+"): "+msg); } return mapper.readTree(response.body()); } catch(ResponseStatusException e){throw e;} catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unable to reach GitHub: "+e.getMessage(),e);} }
    private void setRequestContext(UUID projectId, String email){ requestProject.set(projectId); requestEmail.set(email); }
    private String tokenForRequest(){
        UUID projectId=requestProject.get(); String email=requestEmail.get();
        if(projectId==null) return "";

        Optional<GithubRepository> connected=repoRepository.findByProjectId(projectId);
        if(connected.isPresent()) {
            GithubRepository projectRepository = connected.get();

            // Project GitHub access is project-scoped. Every member uses the
            // installation that the owner selected when the repository was
            // connected; a member's personal GitHub connection is irrelevant.
            if(projectRepository.getGithubInstallationId()!=null) {
                return githubAppService.installationTokenForInstallationId(projectRepository.getGithubInstallationId());
            }

            // Backward compatibility for repositories created before the
            // project-level installation id was introduced.
            if(projectRepository.getConnection()!=null) {
                User ownerUser=projectRepository.getProject().getOwner();
                return githubAppService.installationTokenForUser(ownerUser);
            }
        }

        if(email==null || email.isBlank()) return "";
        return githubAppService.installationTokenForUser(user(email));
    }
    private GithubConnection githubConnection(UUID projectId, String email){
        User u=user(email);
        return getConnection(u.getId());
    }
    private GithubConnection getConnection(UUID userId){
        return connectionRepository.findByUserId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Connect GitHub in Settings before connecting a repository."));
    }
    void clearRequestContext(){ requestProject.remove(); requestEmail.remove(); }

    private GithubRepository repository(UUID id){return repoRepository.findByProjectId(id).orElseThrow(()->bad("Connect a GitHub repository to this project first."));}
    private Project project(UUID id){return projectRepository.findById(id).orElseThrow(()->bad("Project not found."));}
    private User user(String email){return userRepository.findByEmail(email).orElseThrow(()->bad("User not found."));}
    private void access(Project p,String email){User u=user(email); if(p.getOwner().getId().equals(u.getId()))return; if(!memberRepository.existsByProjectIdAndUserId(p.getId(),u.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You do not have access to this project.");}
    private void owner(Project p,User u){if(!p.getOwner().getId().equals(u.getId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the project owner can connect a GitHub repository.");}
    private String normalizeRepo(String s){if(s==null)return null; String v=s.trim().replace("https://github.com/","").replace("http://github.com/","").replace("github.com/","").replaceAll("/+$",""); String[] x=v.split("/"); return x.length==2 && !x[0].isBlank()&&!x[1].isBlank()?x[0]+"/"+x[1]:null;}
    private String normalizePath(String s){return s==null?"":s.replace('\\','/').replaceAll("^/+|/+$","").replaceAll("/+/","/");}
    private String encodePath(String s){return Arrays.stream(String.valueOf(s).split("/",-1)).map(this::url).collect(Collectors.joining("/"));}
    private String url(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
    private ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private String language(String path){String ext="";int i=path.lastIndexOf('.');if(i>=0)ext=path.substring(i+1).toLowerCase();return switch(ext){case"java"->"java";case"js"->"javascript";case"jsx"->"jsx";case"ts"->"typescript";case"tsx"->"tsx";case"py"->"python";case"c"->"c";case"cpp","cc","cxx"->"cpp";case"h","hpp"->"cpp";case"html"->"html";case"css"->"css";case"json"->"json";case"xml"->"xml";case"yml","yaml"->"yaml";case"md"->"markdown";case"sql"->"sql";case"sh"->"shell";case"bat"->"bat";default->"text";};}
}
