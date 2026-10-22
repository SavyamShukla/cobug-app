package com.cobug.storage;

import com.cobug.auth.User;
import com.cobug.projects.Project;
import com.cobug.storage.supabase.SupabaseProjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@Primary
public class DelegatingProjectStorageService implements ProjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(DelegatingProjectStorageService.class);

    private final LocalProjectStorageService localService;
    private final SupabaseProjectStorageService supabaseService;
    private final boolean preferSupabase;

    public DelegatingProjectStorageService(
            LocalProjectStorageService localService,
            SupabaseProjectStorageService supabaseService,
            @Value("${cobug.storage.type:auto}") String storageType
    ) {
        this.localService = localService;
        this.supabaseService = supabaseService;
        this.preferSupabase = !"local".equalsIgnoreCase(storageType);

        if (preferSupabase && supabaseService.isConfigured()) {
            log.info("[COBUG][STORAGE] Active ProjectStorageService provider: SUPABASE (Cloud bucket)");
        } else {
            log.info("[COBUG][STORAGE] Active ProjectStorageService provider: LOCAL (Filesystem)");
        }
    }

    private ProjectStorageService delegate() {
        if (preferSupabase && supabaseService.isConfigured()) {
            return supabaseService;
        }
        return localService;
    }

    @Override
    public void initializeMain(Project project) throws IOException {
        delegate().initializeMain(project);
    }

    @Override
    public void initializeWorkspace(Project project, User user) throws IOException {
        delegate().initializeWorkspace(project, user);
    }

    @Override
    public void saveMainFile(Project project, String relativePath, MultipartFile file) throws IOException {
        delegate().saveMainFile(project, relativePath, file);
    }

    @Override
    public void saveMainFiles(Project project, List<String> relativePaths, List<MultipartFile> files) throws IOException {
        delegate().saveMainFiles(project, relativePaths, files);
    }

    @Override
    public void saveWorkspaceFile(Project project, User user, String relativePath, MultipartFile file) throws IOException {
        delegate().saveWorkspaceFile(project, user, relativePath, file);
    }

    @Override
    public void saveWorkspaceFiles(Project project, User user, List<String> relativePaths, List<MultipartFile> files) throws IOException {
        delegate().saveWorkspaceFiles(project, user, relativePaths, files);
    }

    @Override
    public void saveWorkspaceFileBytes(Project project, User user, String relativePath, byte[] content) throws IOException {
        delegate().saveWorkspaceFileBytes(project, user, relativePath, content);
    }

    @Override
    public void copyMainFileToWorkspace(Project project, User user, String relativePath) throws IOException {
        delegate().copyMainFileToWorkspace(project, user, relativePath);
    }

    @Override
    public byte[] readMainFile(Project project, String relativePath) throws IOException {
        return delegate().readMainFile(project, relativePath);
    }

    @Override
    public byte[] readWorkspaceFile(Project project, User user, String relativePath) throws IOException {
        return delegate().readWorkspaceFile(project, user, relativePath);
    }

    @Override
    public List<StoredFileInfo> listMainFiles(Project project) throws IOException {
        return delegate().listMainFiles(project);
    }

    @Override
    public List<StoredFileInfo> listWorkspaceFiles(Project project, User user) throws IOException {
        return delegate().listWorkspaceFiles(project, user);
    }

    @Override
    public void deleteMainFile(Project project, String relativePath) throws IOException {
        delegate().deleteMainFile(project, relativePath);
    }

    @Override
    public void deleteWorkspaceFile(Project project, User user, String relativePath) throws IOException {
        delegate().deleteWorkspaceFile(project, user, relativePath);
    }

    @Override
    public void deleteProjectStorage(UUID projectId) throws IOException {
        delegate().deleteProjectStorage(projectId);
    }

    @Override
    public void clearMainStorage(UUID projectId) throws IOException {
        delegate().clearMainStorage(projectId);
    }
}
