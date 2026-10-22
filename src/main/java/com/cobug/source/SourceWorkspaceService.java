package com.cobug.source;

import com.cobug.auth.User;
import com.cobug.mainversion.MainVersion;
import com.cobug.mainversion.MainVersionRepository;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SourceWorkspaceService {
    private final ProjectSourceWorkspaceRepository workspaces;
    private final MainVersionRepository versions;
    private final ProjectAccessService access;

    public SourceWorkspaceService(ProjectSourceWorkspaceRepository workspaces,
                                   MainVersionRepository versions,
                                   ProjectAccessService access) {
        this.workspaces = workspaces;
        this.versions = versions;
        this.access = access;
    }

    public Map<String, Object> get(UUID projectId, String email) {
        User user = access.requireMember(projectId, email);
        Project project = access.getProject(projectId);
        return workspaces.findByProjectIdAndUserId(projectId, user.getId())
                .map(this::response)
                .orElseGet(() -> emptyResponse(project));
    }

    public Map<String, Object> save(UUID projectId, String email, SaveRequest request) {
        User user = access.requireMember(projectId, email);
        Project project = access.getProject(projectId);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace metadata is required.");

        ProjectSourceWorkspace state = workspaces.findByProjectIdAndUserId(projectId, user.getId())
                .orElseGet(ProjectSourceWorkspace::new);
        state.setProject(project);
        state.setUser(user);
        state.setLocalPath(cleanPath(request.localPath()));
        state.setBaseMainVersionId(request.baseMainVersionId());
        state.setBaseCommitSha(blankToNull(request.baseCommitSha()));
        state.setSourceRootHash(blankToNull(request.sourceRootHash()));

        if (request.baseMainVersionId() != null) {
            MainVersion version = versions.findById(request.baseMainVersionId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Main version not found."));
            if (!version.getProject().getId().equals(projectId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Main version does not belong to this project.");
            }
            state.setBaseMainVersionNumber(version.getVersionNumber());
        } else {
            state.setBaseMainVersionNumber(null);
        }

        return response(workspaces.save(state));
    }

    public void clear(UUID projectId, String email) {
        User user = access.requireMember(projectId, email);
        workspaces.findByProjectIdAndUserId(projectId, user.getId()).ifPresent(workspaces::delete);
    }

    private Map<String, Object> response(ProjectSourceWorkspace state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", state.getId());
        out.put("projectId", state.getProject().getId());
        out.put("userId", state.getUser().getId());
        out.put("localPath", state.getLocalPath());
        out.put("baseMainVersionId", state.getBaseMainVersionId());
        out.put("baseMainVersionNumber", state.getBaseMainVersionNumber());
        out.put("baseCommitSha", state.getBaseCommitSha());
        out.put("sourceRootHash", state.getSourceRootHash());
        out.put("createdAt", state.getCreatedAt());
        out.put("updatedAt", state.getUpdatedAt());
        return out;
    }

    private Map<String, Object> emptyResponse(Project project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", null);
        out.put("projectId", project.getId());
        out.put("userId", null);
        out.put("localPath", null);
        out.put("baseMainVersionId", null);
        out.put("baseMainVersionNumber", null);
        out.put("baseCommitSha", null);
        out.put("sourceRootHash", null);
        out.put("createdAt", null);
        out.put("updatedAt", null);
        return out;
    }

    private static String cleanPath(String value) {
        if (value == null) return null;
        String path = value.trim();
        return path.isBlank() ? null : path;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record SaveRequest(String localPath, UUID baseMainVersionId, String baseCommitSha, String sourceRootHash) {}
}
