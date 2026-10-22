package com.cobug.social;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {
    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);
    Optional<UserBlock> findByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);
    List<UserBlock> findByBlockerIdOrBlockedId(UUID blockerId, UUID blockedId);
}
