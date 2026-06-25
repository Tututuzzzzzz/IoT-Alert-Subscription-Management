package com.asia.saas.service;

import com.asia.saas.dto.KafkaEventMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private static final String TOPIC = "raw-iot-events";

    public void sendEvent(KafkaEventMessage eventMessage) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(eventMessage);
            String key = String.valueOf(eventMessage.getDeviceId());
            log.info("Publishing event to Kafka topic [{}]: key={}, message={}", TOPIC, key, jsonMessage);
            kafkaTemplate.send(TOPIC, key, jsonMessage);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Kafka event message: {}", e.getMessage(), e);
            throw new RuntimeException("Serialization failure", e);
        }
    }
}
