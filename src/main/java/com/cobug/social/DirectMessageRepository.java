package com.cobug.social;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, UUID> {
    @Query("select m from DirectMessage m where (m.senderId = :a and m.recipientId = :b) or (m.senderId = :b and m.recipientId = :a) order by m.createdAt desc")
    List<DirectMessage> findConversation(@Param("a") UUID a, @Param("b") UUID b, org.springframework.data.domain.Pageable pageable);
}
