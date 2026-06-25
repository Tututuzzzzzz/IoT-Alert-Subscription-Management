package com.asia.saas.controller;

import com.asia.saas.dto.AlertResponse;
import com.asia.saas.entity.Alert;
import com.asia.saas.entity.User;
import com.asia.saas.repository.AlertRepository;
import com.asia.saas.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Slf4j
public class AlertController {

    private final UserRepository userRepository;
    private final AlertRepository alertRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_KEY_PREFIX = "user:alerts:";
    private static final int MAX_LIMIT = 20;

    @GetMapping
    public ResponseEntity<List<AlertResponse>> getNewsfeed() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String redisKey = REDIS_KEY_PREFIX + user.getId();
        
        // 1. Try reading from Redis list
        List<String> cachedAlerts = redisTemplate.opsForList().range(redisKey, 0, MAX_LIMIT - 1);
        List<AlertResponse> responseList = new ArrayList<>();

        if (cachedAlerts != null && !cachedAlerts.isEmpty()) {
            log.info("Serving alerts newsfeed from Redis cache for user {}", username);
            for (String json : cachedAlerts) {
                try {
                    AlertResponse alertResponse = objectMapper.readValue(json, AlertResponse.class);
                    responseList.add(alertResponse);
                } catch (Exception e) {
                    log.error("Failed to parse cached alert JSON: {}", e.getMessage());
                }
            }
            return ResponseEntity.ok(responseList);
        }

        // 2. Cache miss: fallback to database
        log.info("Cache miss. Fetching alerts newsfeed from database for user {}", username);
        List<Alert> dbAlerts = alertRepository.findByUserOrderByCreatedAtDesc(user);
        
        // Cap list at 20 elements
        int limit = Math.min(dbAlerts.size(), MAX_LIMIT);
        for (int i = 0; i < limit; i++) {
            Alert alert = dbAlerts.get(i);
            AlertResponse res = AlertResponse.builder()
                    .id(alert.getId())
                    .deviceId(alert.getDevice().getId())
                    .deviceName(alert.getDevice().getName())
                    .type(alert.getType())
                    .message(alert.getMessage())
                    .level(alert.getLevel())
                    .createdAt(alert.getCreatedAt())
                    .build();
            responseList.add(res);

            // Repopulate cache
            try {
                String json = objectMapper.writeValueAsString(res);
                redisTemplate.opsForList().rightPush(redisKey, json);
            } catch (Exception e) {
                log.error("Failed to serialize and cache database alert: {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(responseList);
    }
}
