package com.asia.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertResponse {
    private Long id;
    private Long deviceId;
    private String deviceName;
    private String type;
    private String message;
    private String level;
    private LocalDateTime createdAt;
}
