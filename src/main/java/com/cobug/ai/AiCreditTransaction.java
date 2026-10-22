package com.cobug.ai;

import com.cobug.auth.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_credit_transactions")
public class AiCreditTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long balanceAfter;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 240)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public AiCreditTransaction() {}

    public AiCreditTransaction(User user, long amount, long balanceAfter, String type, String description) {
        this.user = user;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.type = type;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public long getAmount() { return amount; }
    public long getBalanceAfter() { return balanceAfter; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
