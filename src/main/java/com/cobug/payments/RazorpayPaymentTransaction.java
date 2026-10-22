package com.cobug.payments;

import com.cobug.auth.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "razorpay_payment_transactions")
public class RazorpayPaymentTransaction {
    public enum Status { CREATED, PAID, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;
    @Column(nullable = false, unique = true)
    private String razorpayOrderId;
    @Column(unique = true)
    private String razorpayPaymentId;
    @Column(nullable = false)
    private String purpose;
    @Column(nullable = false)
    private long amount;
    @Column(nullable = false)
    private String currency = "INR";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CREATED;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private String failureReason;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public UUID getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String v) { this.razorpayOrderId = v; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v) { this.razorpayPaymentId = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public long getAmount() { return amount; }
    public void setAmount(long v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime v) { this.paidAt = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }
}
