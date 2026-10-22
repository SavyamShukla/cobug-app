package com.cobug.payments;

import com.cobug.ai.AiSettingsService;
import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RazorpayPaymentService {
    private final UserRepository users;
    private final RazorpayPaymentTransactionRepository transactions;
    private final AiSettingsService aiSettings;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final long plusAmountPaise;
    private final long proAmountPaise;

    public RazorpayPaymentService(
            UserRepository users,
            RazorpayPaymentTransactionRepository transactions,
            AiSettingsService aiSettings,
            @Value("${cobug.razorpay.key-id:}") String keyId,
            @Value("${cobug.razorpay.key-secret:}") String keySecret,
            @Value("${cobug.razorpay.webhook-secret:}") String webhookSecret,
            @Value("${cobug.razorpay.plus-amount-paise:9900}") long plusAmountPaise,
            @Value("${cobug.razorpay.pro-amount-paise:19900}") long proAmountPaise) {
        this.users = users;
        this.transactions = transactions;
        this.aiSettings = aiSettings;
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.plusAmountPaise = plusAmountPaise;
        this.proAmountPaise = proAmountPaise;
    }

    public Config config() {
        return new Config(!keyId.isBlank() && !keySecret.isBlank(), keyId, plusAmountPaise, plusAmountPaise, proAmountPaise, "INR");
    }

    @Transactional
    public OrderResponse createOrder(String email) {
        return createOrder(email, "plus");
    }

    @Transactional
    public OrderResponse createOrder(String email, String plan) {
        requireConfigured();
        User user = findUser(email);
        String selectedPlan = plan == null || plan.isBlank() ? "plus" : plan.toLowerCase().trim();
        boolean isPro = "pro".equals(selectedPlan);
        long targetAmount = isPro ? proAmountPaise : plusAmountPaise;
        String purpose = isPro ? "COBUG_PRO_PLAN" : "COBUG_PLUS_PLAN";
        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            String receipt = "COBUG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            JSONObject options = new JSONObject();
            options.put("amount", targetAmount);
            options.put("currency", "INR");
            options.put("receipt", receipt);
            options.put("notes", new JSONObject()
                    .put("purpose", purpose)
                    .put("plan", selectedPlan)
                    .put("user_id", user.getId().toString()));
            Order order = client.orders.create(options);

            RazorpayPaymentTransaction tx = new RazorpayPaymentTransaction();
            tx.setUser(user);
            tx.setRazorpayOrderId(order.get("id"));
            tx.setPurpose(purpose);
            tx.setAmount(targetAmount);
            tx.setCurrency("INR");
            tx.setStatus(RazorpayPaymentTransaction.Status.CREATED);
            transactions.save(tx);
            return new OrderResponse(order.get("id"), targetAmount, "INR", keyId, selectedPlan);
        } catch (RazorpayException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create Razorpay order: " + e.getMessage(), e);
        }
    }

    @Transactional
    public VerifyResponse verify(String email, VerifyRequest request) {
        requireConfigured();
        if (request == null || blank(request.orderId()) || blank(request.paymentId()) || blank(request.signature())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incomplete Razorpay payment verification data.");
        }
        User user = findUser(email);
        RazorpayPaymentTransaction tx = transactions.findByRazorpayOrderId(request.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Razorpay order not found."));
        if (!tx.getUser().getId().equals(user.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payment order does not belong to this account.");
        String plan = tx.getAmount() >= proAmountPaise || (tx.getPurpose() != null && tx.getPurpose().contains("PRO")) ? "pro" : "plus";
        if (tx.getStatus() == RazorpayPaymentTransaction.Status.PAID) return new VerifyResponse(true, "Payment already verified.", plan);
        if (tx.getAmount() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment transaction amount.");

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", request.orderId());
            attributes.put("razorpay_payment_id", request.paymentId());
            attributes.put("razorpay_signature", request.signature());
            if (!Utils.verifyPaymentSignature(attributes, keySecret)) {
                tx.setStatus(RazorpayPaymentTransaction.Status.FAILED);
                tx.setFailureReason("Invalid payment signature");
                transactions.save(tx);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment signature verification failed.");
            }

            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            Payment payment = client.payments.fetch(request.paymentId());
            String status = payment.get("status");
            Number paidAmount = payment.get("amount");
            String paymentOrderId = payment.get("order_id");
            if (!request.orderId().equals(paymentOrderId) || paidAmount == null || paidAmount.longValue() != tx.getAmount() || !"captured".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Razorpay payment is not captured for this order yet or amount mismatched.");
            }
            settle(tx, request.paymentId());
            return new VerifyResponse(true, "Payment verified and " + plan.toUpperCase() + " plan activated.", plan);
        } catch (RazorpayException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to verify payment with Razorpay: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void handleWebhook(String signature, String rawBody) {
        if (webhookSecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Razorpay webhook secret is not configured.");
        if (blank(signature) || rawBody == null || rawBody.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay webhook request.");
        try {
            if (!Utils.verifyWebhookSignature(rawBody, signature, webhookSecret)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay webhook signature.");
            JSONObject root = new JSONObject(rawBody);
            if (!"payment.captured".equals(root.optString("event", ""))) return;
            JSONObject payment = root.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
            String orderId = payment.optString("order_id", "");
            String paymentId = payment.optString("id", "");
            if (orderId.isBlank() || paymentId.isBlank()) return;
            transactions.findByRazorpayOrderId(orderId).ifPresent(tx -> {
                if (tx.getStatus() == RazorpayPaymentTransaction.Status.PAID) return;
                if (payment.optLong("amount", 0) != tx.getAmount()) return;
                settle(tx, paymentId);
            });
        } catch (RazorpayException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to validate Razorpay webhook.", e);
        }
    }

    private void settle(RazorpayPaymentTransaction tx, String paymentId) {
        if (tx.getStatus() == RazorpayPaymentTransaction.Status.PAID) return;
        tx.setStatus(RazorpayPaymentTransaction.Status.PAID);
        tx.setRazorpayPaymentId(paymentId);
        tx.setPaidAt(LocalDateTime.now());
        tx.setFailureReason(null);
        transactions.save(tx);
        String plan = tx.getAmount() >= proAmountPaise || (tx.getPurpose() != null && tx.getPurpose().contains("PRO")) ? "pro" : "plus";
        aiSettings.activatePlanFromPayment(tx.getUser().getEmail(), plan, "COBUG " + plan.toUpperCase() + " Plan activation");
    }

    private User findUser(String email) {
        return users.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void requireConfigured() {
        if (keyId.isBlank() || keySecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Razorpay is not configured. Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET before starting COBUG.");
    }

    public record Config(boolean configured, String keyId, long amount, long plusAmount, long proAmount, String currency) {}
    public record OrderResponse(String orderId, long amount, String currency, String keyId, String plan) {}
    public record VerifyRequest(String orderId, String paymentId, String signature) {}
    public record VerifyResponse(boolean success, String message, String plan) {}
}
