package com.asia.saas.service;

import com.asia.saas.dto.AlertResponse;
import com.asia.saas.entity.Alert;
import com.asia.saas.entity.Device;
import com.asia.saas.entity.User;
import com.asia.saas.repository.AlertRepository;
import com.asia.saas.websocket.AlertWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    private final AlertRepository alertRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AlertWebSocketHandler webSocketHandler;

    private static final String REDIS_KEY_PREFIX = "user:alerts:";
    private static final int MAX_CACHE_SIZE = 20;

    @Transactional
    public AlertResponse createAlert(User user, Device device, String type, String message, String level) {
        Alert alert = Alert.builder()
                .user(user)
                .device(device)
                .type(type)
                .message(message)
                .level(level)
                .build();

        alert = alertRepository.save(alert);
        log.info("Saved alert {} in database for user {}", alert.getId(), user.getUsername());

        AlertResponse response = AlertResponse.builder()
                .id(alert.getId())
                .deviceId(device.getId())
                .deviceName(device.getName())
                .type(type)
                .message(message)
                .level(level)
                .createdAt(alert.getCreatedAt())
                .build();

        try {
            String json = objectMapper.writeValueAsString(response);
            
            // Push to Redis List and trim to keep at most 20 elements
            String redisKey = REDIS_KEY_PREFIX + user.getId();
            redisTemplate.opsForList().leftPush(redisKey, json);
            redisTemplate.opsForList().trim(redisKey, 0, MAX_CACHE_SIZE - 1);
            log.info("Cached alert {} in Redis for user {}", alert.getId(), user.getUsername());

            // Broadcast to connected WebSockets
            webSocketHandler.sendAlertToUser(user.getId(), json);

        } catch (Exception e) {
            log.error("Failed to cache or broadcast alert: {}", e.getMessage(), e);
        }

        return response;
    }
}
