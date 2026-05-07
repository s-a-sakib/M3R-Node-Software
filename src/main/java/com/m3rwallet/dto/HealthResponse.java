package com.m3rwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthResponse {
    @JsonProperty("status")
    private String status;

    @JsonProperty("message")
    private String message;
}
