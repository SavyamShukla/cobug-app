package com.cobug.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    long countByRecipientIdAndSeenFalse(UUID recipientId);

    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    boolean existsByRecipientIdAndReferenceIdAndKind(
            UUID recipientId,
            UUID referenceId,
            String kind
    );

    Optional<Notification> findFirstByRecipientIdAndReferenceIdAndKind(
            UUID recipientId,
            UUID referenceId,
            String kind
    );

    void deleteByReferenceIdAndReferenceType(UUID referenceId, String referenceType);
}
