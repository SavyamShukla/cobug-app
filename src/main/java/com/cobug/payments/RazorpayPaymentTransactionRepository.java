package com.cobug.payments;

import com.cobug.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RazorpayPaymentTransactionRepository extends JpaRepository<RazorpayPaymentTransaction, UUID> {
    Optional<RazorpayPaymentTransaction> findByRazorpayOrderId(String orderId);
    Optional<RazorpayPaymentTransaction> findByRazorpayPaymentId(String paymentId);
    List<RazorpayPaymentTransaction> findByUserAndStatusOrderByPaidAtDesc(User user, RazorpayPaymentTransaction.Status status);
    List<RazorpayPaymentTransaction> findByUserOrderByCreatedAtDesc(User user);
}
