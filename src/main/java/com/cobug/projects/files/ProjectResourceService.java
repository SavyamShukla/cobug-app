package com.cobug.projects.files;

import com.cobug.activity.ProjectActivityService;
import com.cobug.auth.User;
import com.cobug.projects.Project;
import com.cobug.projects.ProjectAccessService;
import com.cobug.storage.StoragePathUtil;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class ProjectResourceService {
    private final ProjectResourceRepository repository;
    private final ProjectAccessService access;
    private final ProjectActivityService activity;
    private final Path root;
    private static final long MAX_SIZE = 50L * 1024 * 1024;

    public ProjectResourceService(ProjectResourceRepository repository, ProjectAccessService access, ProjectActivityService activity, @org.springframework.beans.factory.annotation.Value("${cobug.storage.root:storage}") String storageRoot) {
        this.repository=repository; this.access=access; this.activity=activity; this.root=Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public List<Map<String,Object>> list(UUID projectId,String email){
        access.requireMember(projectId,email);
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::toMap).toList();
    }

    public List<Map<String,Object>> upload(UUID projectId,String email,List<MultipartFile> files){
        User user=access.requireMember(projectId,email);
        if(files==null||files.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose at least one file.");
        List<Map<String,Object>> result=new ArrayList<>();
        for(MultipartFile file:files){
            if(file==null||file.isEmpty()) continue;
            if(file.getSize()>MAX_SIZE) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Each file must be 50 MB or smaller.");
            String name=safeName(file.getOriginalFilename());
            String storageKey="project-files/"+projectId+"/shared/"+UUID.randomUUID()+"_"+name;
            Path destination=StoragePathUtil.safeResolve(root,storageKey);
            try{
                Files.createDirectories(destination.getParent());
                try(var in=file.getInputStream()){Files.copy(in,destination,StandardCopyOption.REPLACE_EXISTING);}
            }catch(IOException ex){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Unable to store file.",ex);}
            ProjectResource item=new ProjectResource(); item.setProjectId(projectId); item.setUploadedBy(user.getId()); item.setOriginalName(name); item.setStorageKey(storageKey); item.setContentType(file.getContentType()); item.setSize(file.getSize());
            ProjectResource saved=repository.save(item);
            activity.record(projectId,user,"UPLOADED_FILE","FILE",saved.getId().toString(),"Uploaded "+name);
            result.add(toMap(saved));
        }
        return result;
    }

    public Resource open(UUID projectId,UUID resourceId,String email){
        access.requireMember(projectId,email);
        ProjectResource item=find(projectId,resourceId);
        Path p=StoragePathUtil.safeResolve(root,item.getStorageKey());
        if(!Files.isRegularFile(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Stored file not found.");
        return new FileSystemResource(p);
    }

    public ProjectResource find(UUID projectId,UUID id){return repository.findById(id).filter(x->x.getProjectId().equals(projectId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"File not found."));}

    public Map<String,Object> metadata(UUID projectId,UUID resourceId,String email){access.requireMember(projectId,email);return toMap(find(projectId,resourceId));}

    public void delete(UUID projectId,UUID resourceId,String email){
        User user=access.requireMember(projectId,email); ProjectResource item=find(projectId,resourceId);
        if(!item.getUploadedBy().equals(user.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the uploader can delete this file.");
        try{Files.deleteIfExists(StoragePathUtil.safeResolve(root,item.getStorageKey()));}catch(IOException ex){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Unable to delete stored file.",ex);}
        repository.delete(item); activity.record(projectId,user,"DELETED_FILE","FILE",resourceId.toString(),"Deleted "+item.getOriginalName());
    }

    private String safeName(String raw){String n=raw==null?"file":raw.replace('\\','/'); n=n.substring(n.lastIndexOf('/')+1).trim(); if(n.isBlank()||n.equals(".")||n.equals("..")) n="file"; if(n.length()>180)n=n.substring(0,180); return n;}
    private Map<String,Object> toMap(ProjectResource r){return Map.of("id",r.getId(),"name",r.getOriginalName(),"contentType",r.getContentType()==null?"application/octet-stream":r.getContentType(),"size",r.getSize(),"createdAt",r.getCreatedAt(),"uploadedBy",r.getUploadedBy(),"storageFolder","shared");}
}
