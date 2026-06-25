package com.asia.saas.service;

import com.asia.saas.dto.KafkaEventMessage;
import com.asia.saas.entity.Device;
import com.asia.saas.entity.User;
import com.asia.saas.repository.DeviceRepository;
import com.asia.saas.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "raw-iot-events", groupId = "saas-group")
    public void consumeEvent(String messageJson) {
        log.info("Received Kafka event message: {}", messageJson);
        try {
            KafkaEventMessage event = objectMapper.readValue(messageJson, KafkaEventMessage.class);

            User user = userRepository.findById(event.getUserId()).orElse(null);
            Device device = deviceRepository.findById(event.getDeviceId()).orElse(null);

            if (user == null || device == null) {
                log.warn("Discarding event: User or Device not found. User ID: {}, Device ID: {}", 
                        event.getUserId(), event.getDeviceId());
                return;
            }

            String type = event.getType();
            Double value = event.getValue();
            String status = event.getStatus();

            String alertLevel = null;
            String alertMessage = null;

            if ("TEMPERATURE".equalsIgnoreCase(type) && value != null) {
                if (value > 50.0) {
                    alertLevel = "CRITICAL";
                    alertMessage = String.format(java.util.Locale.US, "Dangerous temperature detected: %.1f°C", value);
                } else if (value > 40.0) {
                    alertLevel = "WARNING";
                    alertMessage = String.format(java.util.Locale.US, "High temperature detected: %.1f°C", value);
                }
            } else if ("MOTION".equalsIgnoreCase(type)) {
                if ("DETECTED".equalsIgnoreCase(status) || (value != null && value == 1.0)) {
                    alertLevel = "CRITICAL";
                    alertMessage = "Motion detected in secure zone";
                }
            } else if ("WARNING".equalsIgnoreCase(status) || "CRITICAL".equalsIgnoreCase(status)) {
                alertLevel = status.toUpperCase();
                alertMessage = String.format("Device reported alert status: %s", status);
            }

            if (alertLevel != null) {
                alertService.createAlert(user, device, type, alertMessage, alertLevel);
            }

        } catch (Exception e) {
            log.error("Error processing Kafka message: {}", e.getMessage(), e);
        }
    }
}
