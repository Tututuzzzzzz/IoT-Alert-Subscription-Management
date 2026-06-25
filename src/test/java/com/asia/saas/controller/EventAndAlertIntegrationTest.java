package com.asia.saas.controller;

import com.asia.saas.dto.DeviceEventPayload;
import com.asia.saas.dto.RegisterRequest;
import com.asia.saas.entity.Device;
import com.asia.saas.entity.Role;
import com.asia.saas.entity.User;
import com.asia.saas.repository.AlertRepository;
import com.asia.saas.repository.DeviceRepository;
import com.asia.saas.repository.UserRepository;
import com.asia.saas.service.KafkaConsumerService;
import com.asia.saas.service.KafkaProducerService;
import com.asia.saas.websocket.AlertWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class EventAndAlertIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaConsumerService kafkaConsumerService;

    @MockitoBean
    private KafkaProducerService kafkaProducerService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private AlertWebSocketHandler webSocketHandler;

    private User testUser;
    private Device testDevice;
    private String token;
    private ListOperations<String, String> listOps;

    @BeforeEach
    void setUp() throws Exception {
        alertRepository.deleteAll();
        deviceRepository.deleteAll();
        userRepository.deleteAll();

        // Setup User
        RegisterRequest registerRequest = RegisterRequest.builder()
                .username("eventuser")
                .password("password123")
                .email("eventuser@example.com")
                .role(Role.ROLE_USER)
                .build();

        String responseJson = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        token = objectMapper.readTree(responseJson).get("token").asText();
        testUser = userRepository.findByUsername("eventuser").orElseThrow();

        // Setup Device
        testDevice = Device.builder()
                .user(testUser)
                .name("Test Thermometer")
                .apiKey(UUID.randomUUID().toString())
                .isActive(true)
                .build();
        testDevice = deviceRepository.save(testDevice);

        // Setup Redis Mock
        listOps = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    @Test
    public void shouldDenyIngestionWithoutValidApiKey() throws Exception {
        DeviceEventPayload payload = DeviceEventPayload.builder()
                .type("TEMPERATURE")
                .value(25.5)
                .build();

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void shouldAcceptIngestionWithValidApiKey() throws Exception {
        DeviceEventPayload payload = DeviceEventPayload.builder()
                .type("TEMPERATURE")
                .value(38.2)
                .build();

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", testDevice.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$").value("Event ingested successfully"));

        verify(kafkaProducerService, times(1)).sendEvent(any());
    }

    @Test
    public void shouldProcessKafkaTemperatureEventAndTriggerAlert() {
        String kafkaPayload = String.format(
                "{\"userId\":%d,\"deviceId\":%d,\"deviceName\":\"%s\",\"type\":\"TEMPERATURE\",\"value\":45.5,\"status\":null,\"timestamp\":\"2026-06-25T12:00:00\"}",
                testUser.getId(), testDevice.getId(), testDevice.getName()
        );

        kafkaConsumerService.consumeEvent(kafkaPayload);

        var alerts = alertRepository.findByUserOrderByCreatedAtDesc(testUser);
        assertThat(alerts).hasSize(1);
        var alert = alerts.get(0);
        assertThat(alert.getType()).isEqualTo("TEMPERATURE");
        assertThat(alert.getLevel()).isEqualTo("WARNING");
        assertThat(alert.getMessage()).contains("High temperature detected: 45.5");

        verify(listOps, times(1)).leftPush(eq("user:alerts:" + testUser.getId()), anyString());
        verify(webSocketHandler, times(1)).sendAlertToUser(eq(testUser.getId()), anyString());
    }

    @Test
    public void shouldProcessKafkaMotionEventAndTriggerCriticalAlert() {
        String kafkaPayload = String.format(
                "{\"userId\":%d,\"deviceId\":%d,\"deviceName\":\"%s\",\"type\":\"MOTION\",\"value\":null,\"status\":\"DETECTED\",\"timestamp\":\"2026-06-25T12:00:00\"}",
                testUser.getId(), testDevice.getId(), testDevice.getName()
        );

        kafkaConsumerService.consumeEvent(kafkaPayload);

        var alerts = alertRepository.findByUserOrderByCreatedAtDesc(testUser);
        assertThat(alerts).hasSize(1);
        var alert = alerts.get(0);
        assertThat(alert.getType()).isEqualTo("MOTION");
        assertThat(alert.getLevel()).isEqualTo("CRITICAL");
        assertThat(alert.getMessage()).contains("Motion detected in secure zone");
    }

    @Test
    public void shouldFetchNewsfeedFromRedisCache() throws Exception {
        String mockAlertJson = "{\"id\":99,\"deviceId\":1,\"deviceName\":\"Mock\",\"type\":\"TEMP\",\"message\":\"Warning\",\"level\":\"WARNING\",\"createdAt\":\"2026-06-25T12:00:00\"}";
        when(listOps.range("user:alerts:" + testUser.getId(), 0, 19))
                .thenReturn(Collections.singletonList(mockAlertJson));

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].message").value("Warning"))
                .andExpect(jsonPath("$[0].level").value("WARNING"));
    }
}
