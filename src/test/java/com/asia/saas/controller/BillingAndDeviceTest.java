package com.asia.saas.controller;

import com.asia.saas.dto.DeviceRequest;
import com.asia.saas.dto.RegisterRequest;
import com.asia.saas.entity.Device;
import com.asia.saas.entity.Role;
import com.asia.saas.entity.Subscription;
import com.asia.saas.entity.User;
import com.asia.saas.repository.DeviceRepository;
import com.asia.saas.repository.ProcessedWebhookEventRepository;
import com.asia.saas.repository.SubscriptionRepository;
import com.asia.saas.repository.UserRepository;
import com.asia.saas.service.StripeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class BillingAndDeviceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StripeService stripeService;

    private User testUser;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        deviceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        processedWebhookEventRepository.deleteAll();
        userRepository.deleteAll();

        RegisterRequest registerRequest = RegisterRequest.builder()
                .username("iotuser")
                .password("password123")
                .email("iotuser@example.com")
                .role(Role.ROLE_USER)
                .build();

        String responseJson = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        token = objectMapper.readTree(responseJson).get("token").asText();
        testUser = userRepository.findByUsername("iotuser").orElseThrow();
    }

    @Test
    public void shouldEnforceFreePlanDeviceLimits() throws Exception {
        DeviceRequest dev1 = new DeviceRequest("Device 1");
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dev1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Device 1"))
                .andExpect(jsonPath("$.apiKey").exists())
                .andExpect(jsonPath("$.active").value(true));

        DeviceRequest dev2 = new DeviceRequest("Device 2");
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dev2)))
                .andExpect(status().isCreated());

        DeviceRequest dev3 = new DeviceRequest("Device 3");
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dev3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$").value("Device limit reached for your current subscription plan. Limit: 2"));
    }

    @Test
    public void shouldUpgradeToPremiumPlanViaStripeWebhook() throws Exception {
        String eventId = "evt_test_webhook_123";
        Event mockEvent = mock(Event.class);
        when(mockEvent.getId()).thenReturn(eventId);
        when(mockEvent.getType()).thenReturn("customer.subscription.created");

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        com.stripe.model.Subscription mockSub = mock(com.stripe.model.Subscription.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockSub));

        when(mockSub.getId()).thenReturn("sub_premium_123");
        when(mockSub.getCustomer()).thenReturn("cus_premium_customer");
        when(mockSub.getStatus()).thenReturn("active");

        Map<String, String> metadata = new HashMap<>();
        metadata.put("userId", String.valueOf(testUser.getId()));
        metadata.put("planName", "PREMIUM");
        when(mockSub.getMetadata()).thenReturn(metadata);

        when(mockSub.getCurrentPeriodStart()).thenReturn(1600000000L);
        when(mockSub.getCurrentPeriodEnd()).thenReturn(1700000000L);

        when(stripeService.constructEvent(anyString(), anyString())).thenReturn(mockEvent);

        mockMvc.perform(post("/api/v1/billing/webhook")
                        .header("Stripe-Signature", "t=123,v1=mock_signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        Optional<Subscription> subscriptionOpt = subscriptionRepository.findByUser(testUser);
        assertThat(subscriptionOpt).isPresent();
        Subscription sub = subscriptionOpt.get();
        assertThat(sub.getPlanName()).isEqualTo("PREMIUM");
        assertThat(sub.getDeviceLimit()).isEqualTo(100);
        assertThat(sub.getStatus()).isEqualTo("active");

        for (int i = 1; i <= 5; i++) {
            DeviceRequest dev = new DeviceRequest("Premium Device " + i);
            mockMvc.perform(post("/api/v1/devices")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dev)))
                    .andExpect(status().isCreated());
        }

        assertThat(deviceRepository.countByUser(testUser)).isEqualTo(5);
    }

    @Test
    public void shouldEnforceWebhookIdempotency() throws Exception {
        String eventId = "evt_idempotency_123";
        Event mockEvent = mock(Event.class);
        when(mockEvent.getId()).thenReturn(eventId);
        when(mockEvent.getType()).thenReturn("customer.subscription.created");

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);

        com.stripe.model.Subscription mockSub = mock(com.stripe.model.Subscription.class);
        when(deserializer.getObject()).thenReturn(Optional.of(mockSub));

        when(mockSub.getId()).thenReturn("sub_premium_123");
        when(mockSub.getCustomer()).thenReturn("cus_premium_customer");
        when(mockSub.getStatus()).thenReturn("active");

        Map<String, String> metadata = new HashMap<>();
        metadata.put("userId", String.valueOf(testUser.getId()));
        metadata.put("planName", "PREMIUM");
        when(mockSub.getMetadata()).thenReturn(metadata);

        when(mockSub.getCurrentPeriodStart()).thenReturn(1600000000L);
        when(mockSub.getCurrentPeriodEnd()).thenReturn(1700000000L);

        when(stripeService.constructEvent(anyString(), anyString())).thenReturn(mockEvent);

        mockMvc.perform(post("/api/v1/billing/webhook")
                        .header("Stripe-Signature", "t=123,v1=mock_signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(processedWebhookEventRepository.existsById(eventId)).isTrue();

        String response = mockMvc.perform(post("/api/v1/billing/webhook")
                        .header("Stripe-Signature", "t=123,v1=mock_signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("Webhook handled (Duplicate)");
    }
}
