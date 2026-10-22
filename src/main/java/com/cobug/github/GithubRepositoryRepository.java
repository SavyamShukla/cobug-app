package com.cobug.github;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface GithubRepositoryRepository extends JpaRepository<GithubRepository, UUID> { Optional<GithubRepository> findByProjectId(UUID projectId); void deleteByProjectId(UUID projectId); }
