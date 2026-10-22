package com.cobug.mainversion;

import com.cobug.auth.User;
import com.cobug.github.GithubDtos;
import com.cobug.github.GithubService;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectAccessService;
import com.cobug.storage.ProjectStorageService;
import com.cobug.storage.source.SourceArtifactStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class MainVersionService {
    private static final Logger log = LoggerFactory.getLogger(MainVersionService.class);
    private final MainVersionRepository versions;
    private final MainSourceObjectRepository objects;
    private final MainVersionFileRepository files;
    private final ProjectAccessService access;
    private final GithubService github;
    private final SourceArtifactStorage artifactStorage;
    private final ProjectStorageService storageService;
    private final long largeFileThresholdBytes;

    public MainVersionService(
            MainVersionRepository versions,
            MainSourceObjectRepository objects,
            MainVersionFileRepository files,
            ProjectAccessService access,
            GithubService github,
            SourceArtifactStorage artifactStorage,
            ProjectStorageService storageService,
            @Value("${cobug.source.large-file-threshold-bytes:10485760}") long largeFileThresholdBytes
    ) {
        this.versions = versions;
        this.objects = objects;
        this.files = files;
        this.access = access;
        this.github = github;
        this.artifactStorage = artifactStorage;
        this.storageService = storageService;
        this.largeFileThresholdBytes = largeFileThresholdBytes;
    }

    @Transactional
    public void deleteAllMainData(UUID projectId, String email) {
        access.requireOwner(projectId, email);
        log.info("[MAIN] Permanently deleting all Main folder data for project {} requested by {}", projectId, email);

        // 1. Delete DB version files and versions
        try {
            files.deleteByProjectId(projectId);
            versions.deleteByProjectId(projectId);
            files.deleteOrphanSourceObjects();
        } catch (Exception e) {
            log.warn("[MAIN] Notice deleting DB records for project {}: {}", projectId, e.getMessage());
        }

        // 2. Delete Supabase Storage / local storage files (clears both main and source-artifacts)
        try {
            storageService.clearMainStorage(projectId);
        } catch (Exception e) {
            log.warn("[MAIN] Notice clearing storage files for project {}: {}", projectId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list(UUID projectId, String email) {
        access.requireMember(projectId, email);
        return versions.findByProjectIdOrderByVersionNumberDesc(projectId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(UUID projectId, UUID versionId, String email) {
        access.requireMember(projectId, email);
        return detail(require(projectId, versionId));
    }

    @Transactional
    public Map<String,Object> create(UUID projectId, String email, CreateVersionRequest request) {
        User user = access.requireOwner(projectId, email);
        Project project = access.getProject(projectId);
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A Main version must contain source files.");
        }
        long next = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId).map(v -> v.getVersionNumber() + 1).orElse(1L);
        List<SourceInput> inputs = request.files().stream().filter(Objects::nonNull)
                .filter(f -> f.path() != null && !f.path().isBlank())
                .map(f -> new SourceInput(normalize(f.path()), f.content() == null ? "" : f.content())).toList();
        if (inputs.size() > 10000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Too many source files.");

        MainVersion version = new MainVersion();
        version.setProject(project);
        version.setCreatedBy(user);
        version.setVersionNumber(next);
        version.setMessage(request.message());
        Map<String,MainSourceObject> objectMap = new TreeMap<>();
        for (SourceInput input : inputs) {
            byte[] bytes = input.content().getBytes(StandardCharsets.UTF_8);
            objectMap.put(input.path(), getOrCreateObject(projectId, input.path(), bytes, input.content(), false));
        }
        return detail(persistVersion(version, objectMap));
    }

    @Transactional
    public Map<String,Object> uploadFolder(
            UUID projectId,
            String email,
            MultipartFile[] files,
            String[] paths,
            String message
    ) {
        User user = access.requireOwner(projectId, email);
        Project project = access.getProject(projectId);

        if (files == null || files.length == 0 || paths == null || paths.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A starting point folder must contain source files.");
        }
        if (files.length != paths.length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Files count does not match paths count.");
        }

        long next = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .map(v -> v.getVersionNumber() + 1).orElse(1L);

        MainVersion version = new MainVersion();
        version.setProject(project);
        version.setCreatedBy(user);
        version.setVersionNumber(next);
        version.setMessage(message == null || message.isBlank() ? "Starting point folder uploaded" : message.trim());

        Map<String, MainSourceObject> objectMap = new TreeMap<>();
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            String rawPath = paths[i];
            if (rawPath == null || rawPath.isBlank()) continue;
            String path = normalize(rawPath);

            try {
                byte[] bytes = file.getBytes();
                boolean isText = looksText(bytes);
                String content = isText ? new String(bytes, StandardCharsets.UTF_8) : null;
                objectMap.put(path, getOrCreateObject(projectId, path, bytes, content, !isText));
            } catch (Exception e) {
                log.error("Failed to read uploaded file: {}", path, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process file: " + path);
            }
        }

        if (objectMap.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded folder contained no valid files.");
        }

        return detail(persistVersion(version, objectMap));
    }

    @Transactional
    public Map<String,Object> uploadZip(
            UUID projectId,
            String email,
            MultipartFile zipFile,
            String message
    ) {
        User user = access.requireOwner(projectId, email);
        Project project = access.getProject(projectId);

        if (zipFile == null || zipFile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A starting point zip package must not be empty.");
        }

        long next = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .map(v -> v.getVersionNumber() + 1).orElse(1L);

        MainVersion version = new MainVersion();
        version.setProject(project);
        version.setCreatedBy(user);
        version.setVersionNumber(next);
        version.setMessage(message == null || message.isBlank() ? "Starting point folder uploaded" : message.trim());

        Map<String, MainSourceObject> objectMap = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(zipFile.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String rawPath = entry.getName();
                if (rawPath == null || rawPath.isBlank()) continue;
                String path = normalize(rawPath);
                byte[] bytes = zip.readAllBytes();
                boolean isText = looksText(bytes);
                String content = isText ? new String(bytes, StandardCharsets.UTF_8) : null;
                objectMap.put(path, getOrCreateObject(projectId, path, bytes, content, !isText));
            }
        } catch (Exception e) {
            log.error("Failed to process uploaded zip for project {}", projectId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to extract project zip: " + e.getMessage());
        }

        if (objectMap.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded zip contained no valid files.");
        }

        return detail(persistVersion(version, objectMap));
    }

    @Transactional
    public Map<String,Object> snapshotFromGithub(UUID projectId, String email, String ref, String message) {
        User user = access.requireMember(projectId, email);
        Project project = access.getProject(projectId);
        GithubDtos.SnapshotResponse snapshot = github.snapshot(projectId, email, ref);
        if (snapshot.files() == null || snapshot.files().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The connected GitHub branch contains no project-controlled files.");
        }
        var existing = versions.findByProjectIdAndSourceCommitSha(projectId, snapshot.commitSha());
        if (existing.isPresent()) return detail(existing.get());

        long next = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId).map(v -> v.getVersionNumber() + 1).orElse(1L);
        MainVersion version = new MainVersion();
        version.setProject(project);
        version.setCreatedBy(user);
        version.setVersionNumber(next);
        version.setMessage(message == null || message.isBlank()
                ? "Snapshot from GitHub " + snapshot.commitSha().substring(0, Math.min(7, snapshot.commitSha().length()))
                : message.trim());
        version.setSourceCommitSha(snapshot.commitSha());
        version.setPushedCommitSha(snapshot.commitSha());
        version.setPushedAt(LocalDateTime.now());

        Map<String,MainSourceObject> objectMap = new TreeMap<>();
        for (GithubDtos.SourceFile input : snapshot.files()) {
            byte[] bytes = input.bytes();
            if (bytes == null) bytes = input.content() == null ? new byte[0] : input.content().getBytes(StandardCharsets.UTF_8);
            objectMap.put(input.path(), getOrCreateObject(projectId, input.path(), bytes, input.content(), input.binary()));
        }
        return detail(persistVersion(version, objectMap));
    }

    @Transactional
    public Map<String,Object> mergeChangesIntoMain(UUID projectId, String email, List<GithubDtos.MergeChange> changes, String message) {
        User user = access.requireMember(projectId, email);
        Project project = access.getProject(projectId);
        MainVersion current = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No Main version exists for this project."));

        Map<String,MainSourceObject> source = new TreeMap<>();
        for (MainVersionFile file : files.findByVersionIdOrderByPathAsc(current.getId())) source.put(file.getPath(), file.getSourceObject());

        for (GithubDtos.MergeChange change : changes == null ? List.<GithubDtos.MergeChange>of() : changes) {
            String path = normalize(change.path());
            if (path.isBlank()) continue;
            if (change.deleted()) {
                source.remove(path);
                continue;
            }
            byte[] bytes = change.bytes() != null ? change.bytes() : (change.content() == null ? new byte[0] : change.content().getBytes(StandardCharsets.UTF_8));
            String content = change.bytes() == null ? (change.content() == null ? "" : change.content()) : null;
            source.put(path, getOrCreateObject(projectId, path, bytes, content, change.bytes() != null && !looksText(bytes)));
        }
        if (source.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The resulting Main version would contain no project-controlled files.");

        long next = current.getVersionNumber() + 1;
        MainVersion version = new MainVersion();
        version.setProject(project);
        version.setCreatedBy(user);
        version.setVersionNumber(next);
        version.setMessage(message == null || message.isBlank() ? "Merged review into COBUG Main" : message.trim());
        return detail(persistVersion(version, source));
    }

    @Transactional
    public Map<String,Object> pushCurrentMainToGithub(UUID projectId, String email, String message) {
        User user = access.requireOwner(projectId, email);
        github.requireConnected(projectId);
        MainVersion current = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No Main version exists to push."));

        List<GithubDtos.SourceFile> source = new ArrayList<>();
        for (MainVersionFile file : files.findByVersionIdOrderByPathAsc(current.getId())) {
            MainSourceObject object = file.getSourceObject();
            byte[] bytes = readObjectBytes(object);
            String content = "DATABASE".equalsIgnoreCase(object.getStorageType()) && !object.isBinaryArtifact()
                    ? object.getContent() : null;
            source.add(new GithubDtos.SourceFile(file.getPath(), content, object.getSha256(), object.getSizeBytes(), bytes, object.isBinaryArtifact(), object.getStorageType(), object.getStorageKey()));
        }

        String expected = versions.findTopByProjectIdAndPushedCommitShaIsNotNullOrderByVersionNumberDesc(projectId)
                .map(MainVersion::getPushedCommitSha).orElse(current.getSourceCommitSha());
        GithubDtos.MergeResponse pushed = github.pushSourceState(projectId, email, source, expected,
                message == null || message.isBlank() ? "COBUG: push Main v" + current.getVersionNumber() : message.trim());
        current.setPushedCommitSha(pushed.commitSha());
        current.setPushedAt(LocalDateTime.now());
        versions.save(current);
        Map<String,Object> out = new LinkedHashMap<>(detail(current));
        out.put("pushedCommitSha", pushed.commitSha());
        out.put("pushedAt", current.getPushedAt());
        out.put("pushedBy", user.getName());
        out.put("message", "Main v" + current.getVersionNumber() + " pushed to GitHub.");
        return out;
    }

    @Transactional
    public void clearProjectMain(UUID projectId, String email) {
        access.requireOwner(projectId, email);
        int removed = clearProjectMainData(projectId);
        log.info("Cleared Main source data for project {} during GitHub repository disconnect. Removed {} Main versions.", projectId, removed);
    }

    @Transactional
    public int clearProjectMainData(UUID projectId) {
        int versionCount = versions.findByProjectIdOrderByVersionNumberDesc(projectId).size();
        files.deleteByProjectId(projectId);
        int versionRows = versions.deleteByProjectId(projectId);
        List<MainSourceObject> orphaned = objects.findOrphanSourceObjects();
        for (MainSourceObject object : orphaned) {
            if ("LOCAL_STORAGE".equalsIgnoreCase(object.getStorageType()) && object.getStorageKey() != null) {
                try { artifactStorage.delete(object.getStorageKey()); }
                catch (Exception e) { log.warn("Unable to delete local source artifact {} during Main cleanup: {}", object.getStorageKey(), e.getMessage()); }
            }
            objects.delete(object);
        }
        log.debug("Main cleanup project {}: versions={}, orphanSourceObjects={}", projectId, versionRows, orphaned.size());
        return Math.max(versionCount, versionRows);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> materialize(UUID projectId, UUID versionId, String email) {
        access.requireMember(projectId, email);
        return detail(require(projectId, versionId));
    }

    @Transactional(readOnly = true)
    public byte[] downloadZip(UUID projectId, UUID versionId, String email) {
        access.requireMember(projectId, email);
        MainVersion version = require(projectId, versionId);
        List<MainVersionFile> versionFiles = files.findByVersionIdOrderByPathAsc(version.getId());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (MainVersionFile file : versionFiles) {
                    byte[] content = readObjectBytes(file.getSourceObject());
                    ZipEntry entry = new ZipEntry(file.getPath().replace('\\', '/'));
                    zip.putNextEntry(entry);
                    zip.write(content);
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate Main zip package: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public byte[] downloadCurrentMainZip(UUID projectId, String email) {
        access.requireMember(projectId, email);
        MainVersion current = versions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Main version exists for this project."));
        return downloadZip(projectId, current.getId(), email);
    }

    private MainVersion require(UUID projectId, UUID versionId) {
        MainVersion v = versions.findById(versionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Main version not found."));
        if (!v.getProject().getId().equals(projectId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Main version not found.");
        return v;
    }

    private MainVersion persistVersion(MainVersion version, Map<String,MainSourceObject> objectMap) {
        version.setSourceRootHash(rootHash(objectMap));
        version = versions.save(version);
        List<MainVersionFile> fileList = new ArrayList<>(objectMap.size());
        for (var entry : objectMap.entrySet()) {
            MainVersionFile vf = new MainVersionFile();
            vf.setVersion(version);
            vf.setPath(entry.getKey());
            vf.setSourceObject(entry.getValue());
            fileList.add(vf);
        }
        files.saveAll(fileList);
        return version;
    }

    private MainSourceObject getOrCreateObject(UUID projectId, String path, byte[] bytes, String preferredText, boolean binary) {
        String hash = sha256(bytes);
        Optional<MainSourceObject> existing = objects.findBySha256(hash);
        if (existing.isPresent()) return existing.get();

        MainSourceObject object = new MainSourceObject();
        object.setSha256(hash);
        object.setSizeBytes(bytes.length);
        object.setBinaryArtifact(binary || !looksText(bytes));
        object.setGitBlobSha(gitBlobSha(bytes));
        object.setContentType(contentType(path, object.isBinaryArtifact()));

        boolean large = bytes.length > largeFileThresholdBytes;
        boolean useStorage = large || object.isBinaryArtifact();
        if (useStorage) {
            if (!artifactStorage.isConfigured()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "This project contains a large/binary file ('" + path + "') that must be stored in COBUG local source storage.");
            }
            try {
                object.setStorageKey(artifactStorage.put(projectId, hash, bytes, contentType(path, object.isBinaryArtifact())));
            } catch (Exception e) {
                if (e instanceof ResponseStatusException rse) throw rse;
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to store project artifact in local source storage: " + e.getMessage(), e);
            }
            object.setStorageType("LOCAL_STORAGE");
            // Keep content as an empty DB marker for compatibility with existing
            // installations where main_source_objects.content is still NOT NULL.
            // The actual bytes live in LOCAL_STORAGE and are read through artifactStorage.
            object.setContent("");
        } else {
            object.setStorageType("DATABASE");
            object.setContent(preferredText == null ? new String(bytes, StandardCharsets.UTF_8) : preferredText);
        }
        return objects.save(object);
    }

    private byte[] readObjectBytes(MainSourceObject object) {
        if ("LOCAL_STORAGE".equalsIgnoreCase(object.getStorageType())) {
            try { return artifactStorage.get(object.getStorageKey()); }
            catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read project artifact from local source storage: " + e.getMessage(), e); }
        }
        return object.getContent() == null ? new byte[0] : object.getContent().getBytes(StandardCharsets.UTF_8);
    }

    private Map<String,Object> summary(MainVersion v) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", v.getId()); m.put("projectId", v.getProject().getId()); m.put("versionNumber", v.getVersionNumber());
        m.put("message", v.getMessage()); m.put("createdAt", v.getCreatedAt()); m.put("createdBy", v.getCreatedBy().getName());
        m.put("sourceRootHash", v.getSourceRootHash()); m.put("sourceCommitSha", v.getSourceCommitSha()); m.put("pushedCommitSha", v.getPushedCommitSha()); m.put("pushedAt", v.getPushedAt());
        return m;
    }

    private Map<String,Object> detail(MainVersion v) {
        Map<String,Object> m = new LinkedHashMap<>(summary(v));
        List<Map<String,Object>> list = files.findByVersionIdOrderByPathAsc(v.getId()).stream().map(f -> {
            MainSourceObject object = f.getSourceObject();
            Map<String,Object> x = new LinkedHashMap<>();
            x.put("path", f.getPath()); x.put("sha256", object.getSha256()); x.put("gitBlobSha", object.getGitBlobSha());
            x.put("content", "DATABASE".equalsIgnoreCase(object.getStorageType()) ? object.getContent() : null);
            x.put("sizeBytes", object.getSizeBytes()); x.put("storageType", object.getStorageType()); x.put("binary", object.isBinaryArtifact());
            x.put("storageKey", object.getStorageKey());
            return x;
        }).toList();
        m.put("files", list);
        return m;
    }

    private static String normalize(String p) {
        String n = p == null ? "" : p.replace('\\','/').replaceAll("^/+", "");
        if (n.isBlank() || n.startsWith("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source path: " + p);
        for (String segment : n.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source path (directory traversal not allowed): " + p);
            }
        }
        return n;
    }

    private static boolean looksText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return true;
        int sample = Math.min(bytes.length, 8192);
        for (int i = 0; i < sample; i++) if (bytes[i] == 0) return false;
        return true;
    }

    private static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String rootHash(Map<String,MainSourceObject> map) {
        StringBuilder b = new StringBuilder();
        map.forEach((p,o) -> b.append(p).append('\0').append(o.getSha256()).append('\n'));
        return sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String gitBlobSha(byte[] bytes) {
        try {
            byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8);
            MessageDigest md = MessageDigest.getInstance("SHA-1"); md.update(header); md.update(bytes); return hex(md.digest());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String contentType(String path, boolean binary) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".onnx")) return "application/octet-stream";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".pdf")) return "application/pdf";
        return binary ? "application/octet-stream" : "text/plain; charset=utf-8";
    }

    private static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(bytes.length * 2); for (byte x : bytes) b.append(String.format("%02x", x)); return b.toString(); }

    public record SourceInput(String path, String content) {}
    public record CreateVersionRequest(String message, List<SourceInput> files) {}
}
