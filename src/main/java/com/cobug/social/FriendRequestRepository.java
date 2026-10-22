package com.cobug.social;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, UUID> {
    List<FriendRequest> findByRecipientIdAndStatusOrderByCreatedAtDesc(UUID recipientId, FriendRequestStatus status);
    List<FriendRequest> findByRequesterIdAndStatusOrderByCreatedAtDesc(UUID requesterId, FriendRequestStatus status);
    Optional<FriendRequest> findByRequesterIdAndRecipientId(UUID requesterId, UUID recipientId);
    List<FriendRequest> findByRequesterIdOrRecipientIdOrderByUpdatedAtDesc(UUID requesterId, UUID recipientId);

    void deleteByRequesterIdAndRecipientId(UUID requesterId, UUID recipientId);
}
