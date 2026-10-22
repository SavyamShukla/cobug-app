package com.cobug.chat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessage, UUID> {
    List<ProjectChatMessage> findTop100ByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
