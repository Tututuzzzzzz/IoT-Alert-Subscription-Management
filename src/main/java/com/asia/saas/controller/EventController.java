package com.asia.saas.controller;

import com.asia.saas.dto.DeviceEventPayload;
import com.asia.saas.dto.KafkaEventMessage;
import com.asia.saas.entity.Device;
import com.asia.saas.repository.DeviceRepository;
import com.asia.saas.service.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final DeviceRepository deviceRepository;
    private final KafkaProducerService kafkaProducerService;

    @PostMapping
    public ResponseEntity<String> ingestEvent(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody DeviceEventPayload payload) {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Ingestion request missing X-API-Key header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing API Key");
        }

        Device device = deviceRepository.findByApiKey(apiKey)
                .orElse(null);

        if (device == null) {
            log.warn("Ingestion request with invalid API Key: {}", apiKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API Key");
        }

        if (!device.isActive()) {
            log.warn("Ingestion request from inactive device: {}", device.getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Device is inactive");
        }

        KafkaEventMessage message = KafkaEventMessage.builder()
                .userId(device.getUser().getId())
                .deviceId(device.getId())
                .deviceName(device.getName())
                .type(payload.getType())
                .value(payload.getValue())
                .status(payload.getStatus())
                .timestamp(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        kafkaProducerService.sendEvent(message);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body("Event ingested successfully");
    }
}
