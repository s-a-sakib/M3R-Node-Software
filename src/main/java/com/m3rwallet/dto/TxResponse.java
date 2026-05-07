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
public class TxResponse {
    @JsonProperty("status")
    private String status; // ACCEPTED, REJECTED, UNKNOWN, PENDING, CONFIRMED

    @JsonProperty("txHash")
    private String txHash;

    @JsonProperty("message")
    private String message;
}
