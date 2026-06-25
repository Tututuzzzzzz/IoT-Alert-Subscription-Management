package com.asia.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceEventPayload {
    private String type;
    private Double value;
    private String status;
    private Map<String, Object> metadata;
}
