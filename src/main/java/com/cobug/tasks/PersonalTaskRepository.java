package com.cobug.tasks;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PersonalTaskRepository extends JpaRepository<PersonalTask, UUID> {
    List<PersonalTask> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<PersonalTask> findByIdAndUserId(UUID id, UUID userId);
}
