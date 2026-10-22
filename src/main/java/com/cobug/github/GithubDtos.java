package com.cobug.github;
import java.time.LocalDateTime;
import java.util.List;
public final class GithubDtos {
  private GithubDtos() {}
  public record ConnectRequest(String repository, String branch) {}
  public record RepositoryResponse(String owner,String repository,String fullName,String branch,LocalDateTime connectedAt) {}
  public record AccessibleRepository(String owner,String repository,String fullName,boolean isPrivate,String defaultBranch,String htmlUrl,String updatedAt) {}
  public record TreeNode(String path,String name,String type,String sha,long size,String language) {}
  public record TreeResponse(String repository,String branch,String commitSha,boolean truncated,List<TreeNode> files) {}
  public record FileResponse(String repository,String branch,String path,String sha,String commitSha,String content,boolean binary,long size) {}
  public record WorkspaceRefRequest(List<String> paths) {}
  public record WorkspaceAddResponse(int added, java.util.UUID baseMainVersionId, long baseMainVersionNumber, String baseCommitSha) {}
  public record WorkspaceFileResponse(String id,String repository,String branch,String path,String commitSha,String blobSha,java.util.UUID baseMainVersionId,Long baseMainVersionNumber,LocalDateTime addedAt) {}
  public record WorkspaceResponse(String repository,String branch,List<WorkspaceFileResponse> files) {}
  /** Snapshot artifact. Text source is kept in content; binary/large artifacts carry bytes and storage metadata. */
  public record SourceFile(String path,String content,String sha256,long sizeBytes,byte[] bytes,boolean binary,String storageType,String storageKey) {
    public SourceFile(String path,String content,String sha256,long sizeBytes) { this(path,content,sha256,sizeBytes,content==null?null:content.getBytes(java.nio.charset.StandardCharsets.UTF_8),false,"DATABASE",null); }
  }
  public record SnapshotResponse(String repository,String branch,String commitSha,List<SourceFile> files) {}
  public record MergeChange(String path,String content,String baseSha,boolean deleted,byte[] bytes) {
    public MergeChange(String path,String content,String baseSha,boolean deleted) { this(path,content,baseSha,deleted,null); }
  }
  public record MergeResponse(String repository,String branch,String commitSha,String treeSha) {}
}
