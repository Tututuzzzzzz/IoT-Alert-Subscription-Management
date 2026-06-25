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
public class KafkaEventMessage {
    private Long userId;
    private Long deviceId;
    private String deviceName;
    private String type;
    private Double value;
    private String status;
    private LocalDateTime timestamp;
}
