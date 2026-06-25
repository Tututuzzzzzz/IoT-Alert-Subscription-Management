package com.asia.saas.controller;

import com.asia.saas.entity.ProcessedWebhookEvent;
import com.asia.saas.entity.Subscription;
import com.asia.saas.entity.User;
import com.asia.saas.repository.ProcessedWebhookEventRepository;
import com.asia.saas.repository.SubscriptionRepository;
import com.asia.saas.repository.UserRepository;
import com.asia.saas.service.StripeService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final StripeService stripeService;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event;
        try {
            event = stripeService.constructEvent(payload, sigHeader);
        } catch (SignatureVerificationException e) {
            log.error("Invalid Stripe signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        // Idempotency check
        if (processedWebhookEventRepository.existsById(event.getId())) {
            log.info("Duplicate webhook event ignored: {}", event.getId());
            return ResponseEntity.ok("Webhook handled (Duplicate)");
        }

        log.info("Processing webhook event: {}, type: {}", event.getId(), event.getType());

        try {
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            Optional<StripeObject> stripeObject = dataObjectDeserializer.getObject();

            if (stripeObject.isEmpty()) {
                log.error("Deserialization failed for event: {}", event.getId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Deserialization failed");
            }

            StripeObject obj = stripeObject.get();

            if (event.getType().startsWith("customer.subscription.")) {
                com.stripe.model.Subscription stripeSub = (com.stripe.model.Subscription) obj;
                handleStripeSubscriptionChange(stripeSub, event.getType());
            }

            // Save event ID to database for idempotency
            processedWebhookEventRepository.save(new ProcessedWebhookEvent(event.getId(), LocalDateTime.now()));

            return ResponseEntity.ok("Webhook handled successfully");
        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing failed: " + e.getMessage());
        }
    }

    private void handleStripeSubscriptionChange(com.stripe.model.Subscription stripeSub, String eventType) {
        String stripeSubId = stripeSub.getId();
        String stripeCustomerId = stripeSub.getCustomer();
        String status = stripeSub.getStatus();

        // 1. Get userId from metadata
        String userIdStr = stripeSub.getMetadata() != null ? stripeSub.getMetadata().get("userId") : null;
        User user = null;

        if (userIdStr != null) {
            try {
                user = userRepository.findById(Long.parseLong(userIdStr)).orElse(null);
            } catch (NumberFormatException e) {
                log.warn("Invalid userId format in metadata: {}", userIdStr);
            }
        }

        if (user == null) {
            log.warn("No user found in metadata for subscription: {}. Cannot link subscription.", stripeSubId);
            return;
        }

        final User finalUser = user;
        Optional<Subscription> existingSubOpt = subscriptionRepository.findByUser(finalUser);
        Subscription subscription = existingSubOpt.orElseGet(() -> Subscription.builder().user(finalUser).build());

        subscription.setStripeSubscriptionId(stripeSubId);
        subscription.setStripeCustomerId(stripeCustomerId);
        subscription.setStatus(status);

        String planName = stripeSub.getMetadata() != null ? stripeSub.getMetadata().get("planName") : "PREMIUM";
        subscription.setPlanName(planName);

        if ("active".equalsIgnoreCase(status)) {
            if ("PREMIUM".equalsIgnoreCase(planName)) {
                subscription.setDeviceLimit(100);
            } else {
                subscription.setDeviceLimit(2);
            }
        } else {
            subscription.setDeviceLimit(2);
        }

        if (stripeSub.getCurrentPeriodStart() != null) {
            subscription.setCurrentPeriodStart(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(stripeSub.getCurrentPeriodStart()), ZoneId.of("UTC")));
        }
        if (stripeSub.getCurrentPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()), ZoneId.of("UTC")));
        }

        subscriptionRepository.save(subscription);
        log.info("Successfully synchronized subscription {} for user {} to status: {}, limit: {}", 
                stripeSubId, user.getUsername(), status, subscription.getDeviceLimit());
    }

    @GetMapping("/status")
    public ResponseEntity<?> getSubscriptionStatus(@org.springframework.security.core.annotation.AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }
        Subscription sub = subscriptionRepository.findByUser(user).orElse(null);
        if (sub == null) {
            sub = Subscription.builder()
                    .user(user)
                    .planName("FREE")
                    .status("active")
                    .deviceLimit(2)
                    .build();
            subscriptionRepository.save(sub);
        }
        return ResponseEntity.ok(sub);
    }

    @PostMapping("/upgrade")
    public ResponseEntity<?> upgradeSubscription(@org.springframework.security.core.annotation.AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }
        Subscription sub = subscriptionRepository.findByUser(user)
                .orElse(Subscription.builder().user(user).build());
        
        sub.setPlanName("PREMIUM");
        sub.setStatus("active");
        sub.setDeviceLimit(100);
        sub.setStripeSubscriptionId("sub_sandbox_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        sub.setCurrentPeriodStart(LocalDateTime.now());
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
        
        Subscription saved = subscriptionRepository.save(sub);
        log.info("Sandbox upgraded user {} to Premium", user.getUsername());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/cancel")
    public ResponseEntity<?> cancelSubscription(@org.springframework.security.core.annotation.AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }
        Subscription sub = subscriptionRepository.findByUser(user).orElse(null);
        if (sub != null) {
            sub.setPlanName("FREE");
            sub.setStatus("active");
            sub.setDeviceLimit(2);
            sub.setStripeSubscriptionId(null);
            subscriptionRepository.save(sub);
            log.info("Sandbox downgraded/cancelled subscription for user {}", user.getUsername());
        }
        return ResponseEntity.ok(sub != null ? sub : "No active subscription");
    }
}
