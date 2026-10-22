package com.cobug.payments;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/razorpay")
public class RazorpayPaymentController {
    private final RazorpayPaymentService service;
    public RazorpayPaymentController(RazorpayPaymentService service) { this.service = service; }

    @GetMapping("/config")
    public RazorpayPaymentService.Config config() { return service.config(); }

    public record OrderRequest(String plan) {}

    @PostMapping("/order")
    public RazorpayPaymentService.OrderResponse order(Authentication authentication, @RequestBody(required = false) OrderRequest request) {
        String plan = request != null && request.plan() != null ? request.plan() : "plus";
        return service.createOrder(authentication.getName(), plan);
    }

    @PostMapping("/verify")
    public RazorpayPaymentService.VerifyResponse verify(Authentication authentication, @RequestBody RazorpayPaymentService.VerifyRequest request) { return service.verify(authentication.getName(), request); }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(name = "X-Razorpay-Signature", required = false) String signature, @RequestBody String rawBody) {
        service.handleWebhook(signature, rawBody);
        return ResponseEntity.ok().build();
    }
}
