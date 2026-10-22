package com.cobug.github;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface GithubConnectionRepository extends JpaRepository<GithubConnection, UUID> {
    Optional<GithubConnection> findByUserId(UUID userId);
    Optional<GithubConnection> findByInstallationId(Long installationId);
}
