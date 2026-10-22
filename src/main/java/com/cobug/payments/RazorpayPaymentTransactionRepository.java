package com.cobug.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RazorpayPaymentTransactionRepository extends JpaRepository<RazorpayPaymentTransaction, UUID> {
    Optional<RazorpayPaymentTransaction> findByRazorpayOrderId(String orderId);
    Optional<RazorpayPaymentTransaction> findByRazorpayPaymentId(String paymentId);
}
