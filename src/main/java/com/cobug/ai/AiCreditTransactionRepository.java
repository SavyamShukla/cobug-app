package com.cobug.ai;

import com.cobug.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AiCreditTransactionRepository extends JpaRepository<AiCreditTransaction, UUID> {
    List<AiCreditTransaction> findTop20ByUserOrderByCreatedAtDesc(User user);
}
