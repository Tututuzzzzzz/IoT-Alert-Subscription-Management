package com.asia.saas.controller;

import com.asia.saas.dto.DeviceRequest;
import com.asia.saas.dto.DeviceResponse;
import com.asia.saas.entity.Device;
import com.asia.saas.entity.Subscription;
import com.asia.saas.entity.User;
import com.asia.saas.repository.DeviceRepository;
import com.asia.saas.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@Slf4j
public class DeviceController {

    private final DeviceRepository deviceRepository;
    private final SubscriptionRepository subscriptionRepository;

    @PostMapping
    public ResponseEntity<?> registerDevice(
            @RequestBody DeviceRequest request,
            @AuthenticationPrincipal User user) {

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }

        if (request.getName() == null || request.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Device name is required");
        }

        int limit = 2;
        Subscription subscription = subscriptionRepository.findByUser(user).orElse(null);

        if (subscription != null && "active".equalsIgnoreCase(subscription.getStatus())) {
            limit = subscription.getDeviceLimit();
        }

        int currentCount = deviceRepository.countByUser(user);
        if (currentCount >= limit) {
            log.warn("User {} reached device limit of {}. Current device count: {}", user.getUsername(), limit, currentCount);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Device limit reached for your current subscription plan. Limit: " + limit);
        }

        String apiKey = "dev_" + UUID.randomUUID().toString().replace("-", "");
        Device device = Device.builder()
                .user(user)
                .name(request.getName().trim())
                .apiKey(apiKey)
                .isActive(true)
                .build();

        Device savedDevice = deviceRepository.save(device);
        log.info("Registered device {} for user {}", savedDevice.getId(), user.getUsername());

        DeviceResponse response = DeviceResponse.builder()
                .id(savedDevice.getId())
                .name(savedDevice.getName())
                .apiKey(savedDevice.getApiKey())
                .isActive(savedDevice.isActive())
                .createdAt(savedDevice.getCreatedAt())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<?> getDevices(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }
        java.util.List<DeviceResponse> list = deviceRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(d -> DeviceResponse.builder()
                        .id(d.getId())
                        .name(d.getName())
                        .apiKey(d.getApiKey())
                        .isActive(d.isActive())
                        .createdAt(d.getCreatedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(list);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDevice(@PathVariable Long id, @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }
        Device device = deviceRepository.findById(id).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        if (!device.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        deviceRepository.delete(device);
        log.info("Deleted device {} by user {}", id, user.getUsername());
        return ResponseEntity.ok("Device deleted successfully");
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<?> toggleDevice(@PathVariable Long id, @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized user");
        }
        Device device = deviceRepository.findById(id).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        if (!device.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        device.setActive(!device.isActive());
        Device saved = deviceRepository.save(device);
        log.info("Toggled device {} to active={} by user {}", id, saved.isActive(), user.getUsername());
        return ResponseEntity.ok(DeviceResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .apiKey(saved.getApiKey())
                .isActive(saved.isActive())
                .createdAt(saved.getCreatedAt())
                .build());
    }
}
