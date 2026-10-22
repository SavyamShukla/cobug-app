package com.cobug.ai;

import com.cobug.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AiSettingsRepository extends JpaRepository<AiSettings, UUID> {
    Optional<AiSettings> findByUser(User user);
}
