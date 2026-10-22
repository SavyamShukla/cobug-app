package com.cobug.github;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface GithubInstallStateRepository extends JpaRepository<GithubInstallState, UUID> {
    Optional<GithubInstallState> findByStateValue(String stateValue);
}
