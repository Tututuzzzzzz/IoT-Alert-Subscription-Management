package com.asia.saas.controller;

import com.asia.saas.dto.LoginRequest;
import com.asia.saas.dto.RegisterRequest;
import com.asia.saas.entity.Role;
import com.asia.saas.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    public void shouldRegisterAndLoginSuccessfully() throws Exception {
        // 1. Register User
        RegisterRequest registerRequest = RegisterRequest.builder()
                .username("testuser")
                .password("password123")
                .email("testuser@example.com")
                .role(Role.ROLE_USER)
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        // 2. Login User
        LoginRequest loginRequest = LoginRequest.builder()
                .username("testuser")
                .password("password123")
                .build();

        String responseJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andReturn().getResponse().getContentAsString();

        // Extract token
        String token = objectMapper.readTree(responseJson).get("token").asText();

        // 3. Test accessing protected demo user endpoint without token -> Forbidden (403)
        mockMvc.perform(get("/api/v1/demo/user"))
                .andExpect(status().isForbidden());

        // 4. Test accessing protected demo user endpoint with token -> OK
        mockMvc.perform(get("/api/v1/demo/user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("Hello User! You have successfully accessed a secured user endpoint."));

        // 5. Test accessing protected demo admin endpoint with USER token -> Forbidden (403)
        mockMvc.perform(get("/api/v1/demo/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    public void shouldAllowAdminToAccessAdminEndpoint() throws Exception {
        // Register Admin
        RegisterRequest registerRequest = RegisterRequest.builder()
                .username("testadmin")
                .password("adminpass")
                .email("admin@example.com")
                .role(Role.ROLE_ADMIN)
                .build();

        String responseJson = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(responseJson).get("token").asText();

        // Access admin endpoint with admin token -> OK
        mockMvc.perform(get("/api/v1/demo/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("Hello Admin! You have successfully accessed a secured admin-only endpoint."));
    }
}
